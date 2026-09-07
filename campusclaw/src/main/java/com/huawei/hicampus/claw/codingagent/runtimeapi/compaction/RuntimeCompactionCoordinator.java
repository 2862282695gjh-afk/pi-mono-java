/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.compaction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventProjector;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventProjectorFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 协调已准入压缩的持久化投影、硬超时及一次性资源收尾，不接收或续跑控制队列。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeCompactionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeCompactionCoordinator.class);

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeSessionRepository repository;

    private final RuntimeEventProjectorFactory projectorFactory;

    private final RuntimeExecutionTimeoutScheduler timeoutScheduler;

    private final RuntimeExecutionProperties properties;

    private final Clock clock;

    public RuntimeCompactionCoordinator(
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeSessionRepository repository,
            RuntimeEventProjectorFactory projectorFactory,
            RuntimeExecutionTimeoutScheduler timeoutScheduler,
            RuntimeExecutionProperties properties,
            Clock clock) {
        this.engineRegistry = engineRegistry;
        this.repository = repository;
        this.projectorFactory = projectorFactory;
        this.timeoutScheduler = timeoutScheduler;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 启动已占容量、已持久化为 running 且已分配内部 Usage 身份的压缩。
     *
     * @param holder 仅属于本次执行的已注册 Holder
     * @param execution Holder 当前压缩执行，不可重复启动
     * @param locale 本次投影语言
     * @return 不向底层传播取消或人为完成的只读终态
     */
    public CompletionStage<RuntimeCompactionResultDTO> start(
            RuntimeSessionHolder holder, RuntimeCompactionExecution execution, Locale locale) {
        return engineRegistry.withOperationLock(holder.sessionId(), () -> {
            if (holder.activeExecution().orElse(null) != execution) {
                throw new IllegalStateException("compaction execution does not own the holder");
            }
            execution.beginCompaction();
            new CompactionRun(holder, execution).start(locale);
            return execution.result();
        });
    }

    private final class CompactionRun {
        private final RuntimeSessionHolder holder;

        private final RuntimeCompactionExecution execution;

        private final AtomicReference<CompactionRun> callbackTarget = new AtomicReference<>(this);

        private RuntimeEventProjector projector;

        private Runnable unsubscribe = () -> {};

        // 所有访问均在 Session 操作锁内；终态后屏蔽已复制或迟到的监听回调。
        private boolean finished;

        private CompactionRun(RuntimeSessionHolder holder, RuntimeCompactionExecution execution) {
            this.holder = holder;
            this.execution = execution;
        }

        private void start(Locale locale) {
            AtomicReference<CompactionRun> target = callbackTarget;
            try {
                projector = projectorFactory.createForCompaction(holder, execution, locale);
                unsubscribe = holder.subscribeCompaction(event -> {
                    CompactionRun active = target.get();
                    if (active != null) {
                        active.onCompactionEvent(event);
                    }
                });
                execution.setTimeoutTask(timeoutScheduler.schedule(this::timeout, properties.getMaxDuration()));
                if (!finished) {
                    holder.compact().whenComplete((unused, error) -> {
                        CompactionRun active = target.get();
                        if (active != null) {
                            active.finish(error);
                        }
                    });
                }
            } catch (RuntimeException error) {
                finish(error);
            }
        }

        private void onCompactionEvent(SessionCompactionEvent event) {
            engineRegistry.withOperationLock(holder.sessionId(), () -> {
                if (!finished) {
                    projector.onCompactionEvent(event);
                }
            });
        }

        private void timeout() {
            engineRegistry.withOperationLock(holder.sessionId(), () -> {
                if (!finished) {
                    execution.requestTimeout();
                    finishLocked(new TimeoutException("runtime compaction exceeded its maximum duration"));
                }
            });
        }

        private void finish(Throwable error) {
            engineRegistry.withOperationLock(holder.sessionId(), () -> finishLocked(error));
        }

        private void finishLocked(Throwable error) {
            if (finished) {
                return;
            }
            finished = true;
            callbackTarget.set(null);
            Long sequence = projector == null ? null : projector.lastCompactionEntrySeq();
            Throwable failure = executionFailure(error, sequence);
            failure = cleanup(failure, unsubscribe);
            failure = cleanup(failure, () -> engineRegistry.complete(holder, execution));
            failure = cleanup(
                    failure,
                    () -> repository.finishExecution(
                            holder.sessionId(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
            if (failure != null) {
                // 不记录异常正文或堆栈，避免第三方异常携带本次 Mate 凭据。
                LOGGER.error(
                        "Runtime compaction failed: sessionId={}, errorCode={}, failureType={}",
                        holder.sessionId(),
                        RuntimeErrorCode.COMMAND_EXECUTION_FAILED.name(),
                        failure.getClass().getSimpleName());
            }
            execution.finishCompaction(sequence, failure);
        }

        private Throwable executionFailure(Throwable error, Long sequence) {
            if (error != null) {
                return error;
            }
            if (projector != null && projector.failure() != null) {
                return projector.failure();
            }
            if (execution.abortRequested()) {
                return new CancellationException("runtime compaction was aborted");
            }
            return sequence == null
                    ? new IllegalStateException("compaction completed without an authoritative entry")
                    : null;
        }
    }

    private static Throwable cleanup(Throwable primary, Runnable action) {
        try {
            action.run();
            return primary;
        } catch (RuntimeException error) {
            if (primary == null) {
                return error;
            }
            if (primary != error) {
                primary.addSuppressed(error);
            }
            return primary;
        }
    }
}
