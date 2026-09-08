/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionControlSignalDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionControlRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 批量轮询数据库控制信号并交给原执行实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ExecutionControlPollingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionControlPollingService.class);

    private final RuntimeExecutionControlRepository controls;

    private final ObjectProvider<RuntimeLocalControlDispatcher> dispatcherProvider;

    private final RuntimeExecutionProperties properties;

    private final ExecutorService dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicBoolean polling = new AtomicBoolean();

    private final Set<ExecutionTargetDTO> deliveredStops = ConcurrentHashMap.newKeySet();

    private final Map<ExecutionTargetDTO, String> deliveredConfirmations = new ConcurrentHashMap<>();

    private volatile long retryAfterEpochMilli;

    public ExecutionControlPollingService(
            RuntimeExecutionControlRepository controls,
            ObjectProvider<RuntimeLocalControlDispatcher> dispatcherProvider,
            RuntimeExecutionProperties properties) {
        this.controls = controls;
        this.dispatcherProvider = dispatcherProvider;
        this.properties = properties;
    }

    /**
     * 按固定间隔批量检查本机活动执行的跨实例控制信号。
     */
    @Scheduled(fixedDelayString = "${campusclaw.runtime.execution.control-poll-interval-ms:500}")
    public void poll() {
        if (!readyToPoll() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException exception) {
            retryAfterEpochMilli = System.currentTimeMillis()
                    + properties.getControlPollFailureBackoff().toMillis();
            LOGGER.warn("Runtime execution control polling failed; retrying after backoff", exception);
        } finally {
            polling.set(false);
        }
    }

    /**
     * 在停止事务成功提交后触发本机快速分发。
     *
     * @param target 已提交停止请求的固定目标
     */
    public void dispatchCommittedStop(ExecutionTargetDTO target) {
        RuntimeLocalControlDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            dispatchStop(dispatcher, target);
        }
    }

    /**
     * 在确认事务成功提交后触发本机快速分发。
     *
     * @param confirmingTarget 确认前的固定目标
     * @param toolCallId 工具调用标识
     */
    public void dispatchCommittedConfirmation(ExecutionTargetDTO confirmingTarget, String toolCallId) {
        RuntimeLocalControlDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            dispatchConfirmation(dispatcher, confirmingTarget, toolCallId);
        }
    }

    private boolean readyToPoll() {
        return System.currentTimeMillis() >= retryAfterEpochMilli;
    }

    private void pollOnce() {
        RuntimeLocalControlDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            return;
        }
        List<ExecutionTargetDTO> targets = activeTargets(dispatcher);
        if (targets.isEmpty()) {
            clearDelivered(Set.of());
            return;
        }
        clearDelivered(Set.copyOf(targets));
        dispatchBatches(dispatcher, targets);
    }

    private List<ExecutionTargetDTO> activeTargets(RuntimeLocalControlDispatcher dispatcher) {
        int limit = properties.getMaxActive();
        List<ExecutionTargetDTO> targets = dispatcher.activeTargets(limit);
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        return targets.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(limit)
                .toList();
    }

    private void dispatchBatches(RuntimeLocalControlDispatcher dispatcher, List<ExecutionTargetDTO> targets) {
        int batchSize = properties.getControlPollBatchSize();
        for (int start = 0; start < targets.size(); start += batchSize) {
            List<ExecutionTargetDTO> batch = targets.subList(start, Math.min(start + batchSize, targets.size()));
            controls.findPendingControls(batch).forEach(signal -> dispatch(dispatcher, signal));
        }
    }

    private void clearDelivered(Set<ExecutionTargetDTO> activeTargets) {
        deliveredStops.retainAll(activeTargets);
        deliveredConfirmations.keySet().retainAll(activeTargets);
    }

    private void dispatch(RuntimeLocalControlDispatcher dispatcher, ExecutionControlSignalDTO signal) {
        if (signal.isStopRequested()) {
            dispatchStop(dispatcher, signal.target());
        } else if (signal.getToolCallId() != null && !signal.getToolCallId().isBlank()) {
            dispatchConfirmation(dispatcher, signal.target(), signal.getToolCallId());
        }
    }

    private void dispatchStop(RuntimeLocalControlDispatcher dispatcher, ExecutionTargetDTO target) {
        if (!deliveredStops.add(target)) {
            return;
        }
        dispatchExecutor.submit(() -> deliverStop(dispatcher, target));
    }

    private void deliverStop(RuntimeLocalControlDispatcher dispatcher, ExecutionTargetDTO target) {
        try {
            if (!dispatcher.dispatchStop(target)) {
                deliveredStops.remove(target);
            }
        } catch (RuntimeException exception) {
            deliveredStops.remove(target);
            LOGGER.warn("Failed to dispatch a stop request to the local Runtime execution", exception);
        }
    }

    private void dispatchConfirmation(
            RuntimeLocalControlDispatcher dispatcher, ExecutionTargetDTO target, String toolCallId) {
        if (deliveredConfirmations.putIfAbsent(target, toolCallId) != null) {
            return;
        }
        dispatchExecutor.submit(() -> deliverConfirmation(dispatcher, target, toolCallId));
    }

    private void deliverConfirmation(
            RuntimeLocalControlDispatcher dispatcher, ExecutionTargetDTO target, String toolCallId) {
        try {
            if (!dispatcher.dispatchConfirmation(target, toolCallId)) {
                deliveredConfirmations.remove(target, toolCallId);
            }
        } catch (RuntimeException exception) {
            deliveredConfirmations.remove(target, toolCallId);
            LOGGER.warn("Failed to dispatch tool confirmation to the local Runtime execution", exception);
        }
    }

    /**
     * 应用停止时终止尚未完成的本机控制分发任务。
     */
    @PreDestroy
    public void close() {
        dispatchExecutor.shutdownNow();
    }
}
