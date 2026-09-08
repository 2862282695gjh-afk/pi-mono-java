/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 协调 Agent 执行、控制消息续跑、超时、持久化收尾和资源释放。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeExecutionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeExecutionCoordinator.class);

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeSessionRepository repository;

    private final RuntimeEventProjectorFactory projectorFactory;

    private final RuntimeExecutionTimeoutScheduler timeoutScheduler;

    private final RuntimeExecutionProperties executionProperties;

    private final RuntimeTerminalEventFactory terminalEventFactory;

    private final Clock clock;

    public RuntimeExecutionCoordinator(
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeSessionRepository repository,
            RuntimeEventProjectorFactory projectorFactory,
            RuntimeExecutionTimeoutScheduler timeoutScheduler,
            RuntimeExecutionProperties executionProperties,
            RuntimeTerminalEventFactory terminalEventFactory,
            Clock clock) {
        this.engineRegistry = engineRegistry;
        this.repository = repository;
        this.projectorFactory = projectorFactory;
        this.timeoutScheduler = timeoutScheduler;
        this.executionProperties = executionProperties;
        this.terminalEventFactory = terminalEventFactory;
        this.clock = clock;
    }

    public void start(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, UserMessage message, Locale locale) {
        RuntimeEventProjector projector;
        try {
            projector = projectorFactory.create(holder, execution, message, locale);
        } catch (RuntimeException error) {
            handleAcceptedStartFailure(holder, execution, error, locale);
            return;
        }
        RuntimeSubscriptions subscriptions = RuntimeSubscriptions.empty();
        try {
            subscriptions = subscribe(holder, projector);
            scheduleTimeout(holder, execution);
            CompletableFuture<Void> future = holder.prompt(message);
            RuntimeSubscriptions finalSubscriptions = subscriptions;
            future.whenComplete(
                    (unused, error) -> finish(holder, execution, projector, finalSubscriptions, error, locale));
        } catch (RuntimeException error) {
            finish(holder, execution, projector, subscriptions, error, locale);
        }
    }

    /**
     * 已接受用户事件后启动失败时，按执行失败完成持久化并释放运行资源。
     *
     * @param holder 已注册的 Session 句柄
     * @param execution 已接受事件对应的活动执行
     * @param error 启动失败原因
     * @param locale 本次响应语言
     */
    public void handleAcceptedStartFailure(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, RuntimeException error, Locale locale) {
        try {
            engineRegistry.withOperationLock(
                    holder.sessionId(), () -> completeAcceptedStartFailure(holder, execution, error, locale));
        } catch (RuntimeException finalizationError) {
            Throwable failure = combineFailures(error, finalizationError);
            failure = completeOutput(execution, failure);
            execution.complete(failure);
            recordFailure(holder.sessionId(), failure, true);
        }
    }

    private void completeAcceptedStartFailure(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, RuntimeException error, Locale locale) {
        execution.closeControls();
        Throwable failure = finishPersistence(holder.sessionId(), error);
        failure = releaseExecution(holder, execution, RuntimeSubscriptions.empty(), failure);
        failure = emitTerminal(execution, StopReason.ERROR, failure, locale);
        failure = completeOutput(execution, failure);
        execution.complete(failure);
        recordFailure(holder.sessionId(), failure, true);
    }

    private static RuntimeSubscriptions subscribe(RuntimeSessionHolder holder, RuntimeEventProjector projector) {
        Runnable agent = holder.agent().subscribe(projector::onEvent);
        Runnable compaction = holder.subscribeCompaction(projector::onCompactionEvent);
        return new RuntimeSubscriptions(agent, compaction);
    }

    private void scheduleTimeout(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        var task = timeoutScheduler.schedule(
                () -> timeoutExecution(holder, execution), executionProperties.getMaxDuration());
        execution.setTimeoutTask(task);
    }

    private void timeoutExecution(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        if (holder.activeExecution().filter(active -> active == execution).isEmpty()) {
            return;
        }
        execution.requestTimeout();
        holder.agent().clearSteeringQueue();
        holder.agent().clearFollowUpQueue();
        holder.abort();
    }

    private void finish(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            RuntimeSubscriptions subscriptions,
            Throwable executionError,
            Locale locale) {
        engineRegistry.withOperationLock(holder.sessionId(), () -> {
            if (!continueQueuedExecution(holder, execution, projector, executionError, locale, subscriptions)) {
                completeExecution(holder, execution, projector, subscriptions, executionError, locale);
            }
        });
    }

    private boolean continueQueuedExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Throwable executionError,
            Locale locale,
            RuntimeSubscriptions subscriptions) {
        if (!canContinue(holder, execution, projector, executionError)) {
            return false;
        }
        try {
            holder.continueQueuedExecution()
                    .whenComplete(
                            (unused, error) -> finish(holder, execution, projector, subscriptions, error, locale));
        } catch (RuntimeException error) {
            completeExecution(holder, execution, projector, subscriptions, error, locale);
        }
        return true;
    }

    private static boolean canContinue(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Throwable executionError) {
        return executionError == null
                && projector.failure() == null
                && projector.terminalReason() != StopReason.ERROR
                && projector.terminalReason() != StopReason.ABORTED
                && execution.acceptingControls()
                && holder.agent().hasQueuedControlMessages();
    }

    private void completeExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            RuntimeSubscriptions subscriptions,
            Throwable executionError,
            Locale locale) {
        execution.closeControls();
        Throwable failure = executionFailure(execution, executionError, projector);
        failure = finishPersistence(holder.sessionId(), failure);
        failure = releaseExecution(holder, execution, subscriptions, failure);
        failure = emitTerminal(execution, projector.terminalReason(), failure, locale);
        failure = completeOutput(execution, failure);
        execution.complete(failure);
        recordFailure(holder.sessionId(), failure, hasUnreportedTerminalError(projector));
    }

    private static Throwable executionFailure(
            RuntimeActiveExecution execution, Throwable executionError, RuntimeEventProjector projector) {
        if (execution.timedOut()) {
            return new TimeoutException("runtime execution exceeded its maximum duration");
        }
        return executionError != null ? executionError : projector.failure();
    }

    private Throwable finishPersistence(String sessionId, Throwable failure) {
        try {
            repository.finishExecution(sessionId, now());
            return failure;
        } catch (RuntimeException persistenceError) {
            return combineFailures(failure, persistenceError);
        }
    }

    private Throwable releaseExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeSubscriptions subscriptions,
            Throwable failure) {
        Throwable result = failure;
        try {
            subscriptions.unsubscribe();
        } catch (RuntimeException unsubscribeError) {
            result = combineFailures(result, unsubscribeError);
        }
        try {
            engineRegistry.complete(holder, execution);
        } catch (RuntimeException releaseError) {
            result = combineFailures(result, releaseError);
        }
        return result;
    }

    private Throwable emitTerminal(
            RuntimeActiveExecution execution, StopReason reason, Throwable failure, Locale locale) {
        try {
            terminalEventFactory.emit(execution.output(), execution, reason, failure, locale);
            return failure;
        } catch (RuntimeException terminalError) {
            return combineFailures(failure, terminalError);
        }
    }

    private static Throwable completeOutput(RuntimeActiveExecution execution, Throwable failure) {
        try {
            execution.output().complete();
            return failure;
        } catch (RuntimeException outputError) {
            return combineFailures(failure, outputError);
        }
    }

    private static Throwable combineFailures(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static boolean hasUnreportedTerminalError(RuntimeEventProjector projector) {
        return projector.terminalReason() == StopReason.ERROR && projector.terminalErrorCode() == null;
    }

    private static void recordFailure(String sessionId, Throwable failure, boolean unreportedTerminalError) {
        if (failure != null) {
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.execution")
                    .addKeyValue("errorCode", RuntimeErrorCode.SESSION_EXECUTION_FAILED.name())
                    .addKeyValue("sessionId", sessionId)
                    .setCause(failure)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.execution",
                            RuntimeErrorCode.SESSION_EXECUTION_FAILED.name());
        } else if (unreportedTerminalError) {
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.execution")
                    .addKeyValue("errorCode", RuntimeErrorCode.SESSION_EXECUTION_FAILED.name())
                    .addKeyValue("sessionId", sessionId)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.execution",
                            RuntimeErrorCode.SESSION_EXECUTION_FAILED.name());
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record RuntimeSubscriptions(Runnable agent, Runnable compaction) {
        private static RuntimeSubscriptions empty() {
            return new RuntimeSubscriptions(() -> {}, () -> {});
        }

        private void unsubscribe() {
            agent.run();
            compaction.run();
        }
    }
}
