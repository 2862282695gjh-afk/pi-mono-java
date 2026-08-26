/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeFailures;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.springframework.stereotype.Component;

/**
 * 协调 Agent 执行、控制消息续跑、超时、持久化收尾和资源释放。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeExecutionCoordinator {
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
        RuntimeEventProjector projector = projectorFactory.create(holder, execution, message);
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
        engineRegistry.lockOperation(holder.sessionId());
        try {
            if (!continueQueuedExecution(holder, execution, projector, executionError, locale, subscriptions)) {
                completeExecution(holder, execution, projector, subscriptions, executionError, locale);
            }
        } finally {
            engineRegistry.unlockOperation(holder.sessionId());
        }
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
        recordFailure(holder.sessionId(), projector, failure);
        terminalEventFactory.emit(execution.eventStream(), execution, projector.terminalReason(), failure, locale);
        execution.eventStream().complete();
        execution.complete(failure);
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
        } finally {
            engineRegistry.complete(holder, execution);
        }
        return result;
    }

    private static Throwable combineFailures(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    private static void recordFailure(String sessionId, RuntimeEventProjector projector, Throwable failure) {
        if (failure != null) {
            RuntimeFailures.record(
                    "runtime.execution", RuntimeErrorCode.SESSION_EXECUTION_FAILED, failure, "sessionId", sessionId);
        } else if (projector.terminalReason() == StopReason.ERROR && projector.terminalErrorCode() == null) {
            RuntimeFailures.record(
                    "runtime.execution", RuntimeErrorCode.SESSION_EXECUTION_FAILED, "sessionId", sessionId);
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
