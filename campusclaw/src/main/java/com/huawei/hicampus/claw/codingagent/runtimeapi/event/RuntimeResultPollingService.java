/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ResultWaitTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SegmentEventBatchDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionResultRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 批量补读本机观察响应所等待的权威执行结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeResultPollingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeResultPollingService.class);

    private final RuntimeExecutionResultRepository results;

    private final RuntimeResultWaitRegistry waits;

    private final RuntimeEventProperties properties;

    private final ExecutorService notificationExecutor;

    private final AtomicBoolean polling = new AtomicBoolean();

    private volatile long retryAfterEpochMilli;

    @Autowired
    public RuntimeResultPollingService(
            RuntimeExecutionResultRepository results,
            RuntimeResultWaitRegistry waits,
            RuntimeEventProperties properties) {
        this(results, waits, properties, newNotificationExecutor(properties.getResultPollBatchSize()));
    }

    RuntimeResultPollingService(
            RuntimeExecutionResultRepository results,
            RuntimeResultWaitRegistry waits,
            RuntimeEventProperties properties,
            ExecutorService notificationExecutor) {
        this.results = results;
        this.waits = waits;
        this.properties = properties;
        this.notificationExecutor = notificationExecutor;
    }

    /**
     * 按固定周期补读一批去重目标；上一批未结束时不积压新批次。
     */
    @Scheduled(fixedDelayString = "${campusclaw.runtime.events.result-poll-interval-ms:500}")
    public void poll() {
        if (!readyToPoll() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            pollBatch(waits.claimTargets(properties.getResultPollBatchSize()));
        } finally {
            polling.set(false);
        }
    }

    /**
     * 在权威事件事务成功提交后异步触发相同补读入口。
     *
     * @param target 已提交事件所属固定目标
     */
    public void notifyCommitted(ExecutionTargetDTO target) {
        try {
            notificationExecutor.execute(() -> pollNotified(target));
        } catch (RuntimeException exception) {
            LOGGER.debug("Runtime result fast-path queue is full; scheduled polling will retry", exception);
        }
    }

    private void pollNotified(ExecutionTargetDTO target) {
        if (readyToPoll()) {
            pollBatch(waits.claimTarget(target));
        }
    }

    private void pollBatch(List<ResultWaitTargetDTO> claimedTargets) {
        for (int index = 0; index < claimedTargets.size(); index++) {
            if (!pollClaimed(claimedTargets.get(index))) {
                claimedTargets.subList(index + 1, claimedTargets.size()).forEach(waits::release);
                return;
            }
        }
    }

    private boolean pollClaimed(ResultWaitTargetDTO claimed) {
        try {
            if (claimed.mode() == RuntimeResultWaitMode.EXECUTION_TERMINAL) {
                pollExecutionTerminal(claimed);
            } else {
                pollSegmentEvents(claimed);
            }
            return true;
        } catch (RuntimeException exception) {
            retryAfterEpochMilli = System.currentTimeMillis()
                    + properties.getResultPollFailureBackoff().toMillis();
            waits.release(claimed);
            LOGGER.warn("Runtime committed result polling failed; scheduled polling will retry", exception);
            return false;
        }
    }

    private void pollExecutionTerminal(ResultWaitTargetDTO claimed) {
        Optional<CommittedEventDTO> terminal = results.findExecutionTerminal(claimed.target());
        if (terminal.isEmpty()) {
            waits.release(claimed);
            return;
        }
        waits.deliver(claimed, List.of(terminal.orElseThrow()), true);
    }

    private void pollSegmentEvents(ResultWaitTargetDTO claimed) {
        Optional<SegmentEventBatchDTO> batch =
                results.readSegmentEvents(claimed.target(), claimed.afterSeq(), properties.getResultReadLimit());
        if (batch.isEmpty()) {
            waits.release(claimed);
            return;
        }
        SegmentEventBatchDTO committed = batch.orElseThrow();
        if (committed.getEvents().isEmpty() && !committed.isTerminal()) {
            waits.release(claimed);
            return;
        }
        waits.deliver(claimed, committed.getEvents(), committed.isTerminal());
    }

    private boolean readyToPoll() {
        return System.currentTimeMillis() >= retryAfterEpochMilli;
    }

    private static ExecutorService newNotificationExecutor(int capacity) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofVirtual().name("runtime-result-notification-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 应用停止时终止尚未执行的提交后快速补读任务。
     */
    @PreDestroy
    public void close() {
        notificationExecutor.shutdownNow();
    }
}
