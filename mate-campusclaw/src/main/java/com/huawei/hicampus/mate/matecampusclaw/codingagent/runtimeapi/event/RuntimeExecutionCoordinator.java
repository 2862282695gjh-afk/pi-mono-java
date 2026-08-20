/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

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
    private static final Logger log = LoggerFactory.getLogger(RuntimeExecutionCoordinator.class);

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
        Runnable unsubscribe = () -> {};
        try {
            unsubscribe = holder.agent().subscribe(projector::onEvent);
            scheduleTimeout(holder, execution);
            CompletableFuture<Void> future = holder.agent().prompt(message);
            Runnable finalUnsubscribe = unsubscribe;
            future.whenComplete(
                    (unused, error) -> finish(holder, execution, projector, finalUnsubscribe, error, locale));
        } catch (RuntimeException error) {
            finish(holder, execution, projector, unsubscribe, error, locale);
        }
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
        holder.agent().abort();
    }

    private void finish(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Runnable unsubscribe,
            Throwable executionError,
            Locale locale) {
        engineRegistry.lockOperation(holder.sessionId());
        try {
            if (!continueQueuedExecution(holder, execution, projector, executionError, locale, unsubscribe)) {
                completeExecution(holder, execution, projector, unsubscribe, executionError, locale);
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
            Runnable unsubscribe) {
        if (!canContinue(holder, execution, projector, executionError)) {
            return false;
        }
        try {
            holder.agent()
                    .continueQueuedExecution()
                    .whenComplete((unused, error) -> finish(holder, execution, projector, unsubscribe, error, locale));
        } catch (RuntimeException error) {
            completeExecution(holder, execution, projector, unsubscribe, error, locale);
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
            Runnable unsubscribe,
            Throwable executionError,
            Locale locale) {
        execution.closeControls();
        Throwable failure = executionFailure(execution, executionError, projector);
        failure = finishPersistence(holder.sessionId(), failure);
        failure = releaseExecution(holder, execution, unsubscribe, failure);
        logFailure(holder.sessionId(), failure);
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
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, Runnable unsubscribe, Throwable failure) {
        Throwable result = failure;
        try {
            unsubscribe.run();
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

    private static void logFailure(String sessionId, Throwable failure) {
        if (failure != null) {
            log.warn("Runtime execution failed for session {}", sessionId, failure);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
