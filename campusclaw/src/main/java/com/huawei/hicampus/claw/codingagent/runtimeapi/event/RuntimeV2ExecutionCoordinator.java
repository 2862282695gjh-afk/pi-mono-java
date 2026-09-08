/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeTerminalRetryScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 协调 v2 Agent 执行，并以固定执行目标提交唯一 idle 终态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeV2ExecutionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeV2ExecutionCoordinator.class);

    private final RuntimeSessionEngineRegistry engines;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeV2EventProjectorFactory projectors;

    private final RuntimeExecutionTimeoutScheduler timeouts;

    private final RuntimeTerminalRetryScheduler terminalRetries;

    private final RuntimeExecutionProperties properties;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeV2EventEncoder encoder;

    private final Clock clock;

    public RuntimeV2ExecutionCoordinator(
            RuntimeSessionEngineRegistry engines,
            RuntimeExecutionPersistenceService persistence,
            RuntimeV2EventProjectorFactory projectors,
            RuntimeExecutionTimeoutScheduler timeouts,
            RuntimeTerminalRetryScheduler terminalRetries,
            RuntimeExecutionProperties properties,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeCommittedEventFactory events,
            RuntimeV2EventEncoder encoder,
            Clock clock) {
        this.engines = engines;
        this.persistence = persistence;
        this.projectors = projectors;
        this.timeouts = timeouts;
        this.terminalRetries = terminalRetries;
        this.properties = properties;
        this.codec = codec;
        this.ids = ids;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
    }

    public void start(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, UserMessage message, Locale locale) {
        RuntimeV2EventProjector projector;
        try {
            projector = projectors.create(holder, execution, message, locale);
            execution.installConfirmationHandler(projector::beforeToolCall);
        } catch (RuntimeException error) {
            handleAcceptedStartFailure(holder, execution, error, locale);
            return;
        }
        RuntimeSubscriptionsDTO subscriptions = RuntimeSubscriptionsDTO.empty();
        try {
            subscriptions = subscribe(holder, projector);
            scheduleTimeout(holder, execution);
            CompletableFuture<Void> future = holder.prompt(message);
            RuntimeSubscriptionsDTO registered = subscriptions;
            future.whenComplete((unused, error) -> finish(holder, execution, projector, registered, error, locale));
        } catch (RuntimeException error) {
            handleAcceptedStartFailure(holder, execution, subscriptions, error, locale);
        }
    }

    public void handleAcceptedStartFailure(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, RuntimeException error, Locale locale) {
        if (execution.beginTerminalFinalization()) {
            TerminalPlanDTO plan = terminalPlan(
                    execution, RuntimeExecutionTerminalReason.FAILED, "EXECUTION_START_FAILED", locale, error);
            finalizeTerminal(holder, execution, RuntimeSubscriptionsDTO.empty(), plan);
        }
    }

    private void handleAcceptedStartFailure(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeSubscriptionsDTO subscriptions,
            RuntimeException error,
            Locale locale) {
        if (execution.beginTerminalFinalization()) {
            TerminalPlanDTO plan = terminalPlan(
                    execution, RuntimeExecutionTerminalReason.FAILED, "EXECUTION_START_FAILED", locale, error);
            finalizeTerminal(holder, execution, subscriptions, plan);
        }
    }

    private static RuntimeSubscriptionsDTO subscribe(RuntimeSessionHolder holder, RuntimeV2EventProjector projector) {
        Runnable agent = holder.agent().subscribe(projector::onEvent);
        try {
            Runnable compaction = holder.subscribeCompaction(projector::onCompactionEvent);
            return new RuntimeSubscriptionsDTO(agent, compaction);
        } catch (RuntimeException error) {
            unsubscribeAfterRegistrationFailure(agent, error);
            throw error;
        }
    }

    private static void unsubscribeAfterRegistrationFailure(Runnable subscription, RuntimeException failure) {
        try {
            subscription.run();
        } catch (RuntimeException unsubscribeError) {
            failure.addSuppressed(unsubscribeError);
        }
    }

    private void scheduleTimeout(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        var task = timeouts.schedule(() -> timeout(holder, execution), properties.getMaxDuration());
        execution.setTimeoutTask(task);
    }

    private void timeout(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        if (holder.activeExecution().filter(active -> active == execution).isEmpty()) {
            return;
        }
        execution.requestTimeout();
        holder.abort();
        execution.cancelToolConfirmation(new TimeoutException("runtime execution exceeded its maximum duration"));
    }

    private void finish(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeV2EventProjector projector,
            RuntimeSubscriptionsDTO subscriptions,
            Throwable executionError,
            Locale locale) {
        if (execution.beginTerminalFinalization()) {
            TerminalPlanDTO plan = executionPlan(execution, projector, executionError, locale);
            finalizeTerminal(holder, execution, subscriptions, plan);
        }
    }

    private TerminalPlanDTO executionPlan(
            RuntimeActiveExecution execution,
            RuntimeV2EventProjector projector,
            Throwable executionError,
            Locale locale) {
        try {
            Throwable failure = executionFailure(execution, executionError, projector);
            TerminalOutcomeDTO outcome = terminalOutcome(execution, projector, failure);
            return terminalPlan(execution, outcome.reason(), outcome.errorCode(), locale, failure);
        } catch (RuntimeException projectionError) {
            Throwable failure = combineFailures(executionError, projectionError);
            return terminalPlan(
                    execution, RuntimeExecutionTerminalReason.FAILED, "AGENT_EXECUTION_FAILED", locale, failure);
        }
    }

    private void finalizeTerminal(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeSubscriptionsDTO subscriptions,
            TerminalPlanDTO plan) {
        try {
            engines.withOperationLock(
                    holder.sessionId(), () -> commitAndRelease(holder, execution, subscriptions, plan, true));
        } catch (RuntimeException terminalError) {
            deferTerminal(holder, execution, subscriptions, plan, terminalError);
        }
    }

    private static Throwable executionFailure(
            RuntimeActiveExecution execution, Throwable executionError, RuntimeV2EventProjector projector) {
        if (execution.timedOut()) {
            return new TimeoutException("runtime execution exceeded its maximum duration");
        }
        return executionError != null ? executionError : projector.failure();
    }

    private static TerminalOutcomeDTO terminalOutcome(
            RuntimeActiveExecution execution, RuntimeV2EventProjector projector, Throwable failure) {
        if (projector.terminalReason() == StopReason.ABORTED) {
            return new TerminalOutcomeDTO(RuntimeExecutionTerminalReason.TERMINATED, null);
        }
        if (failure != null) {
            String errorCode = projector.terminalReason() == StopReason.ERROR
                    ? executionErrorCode(projector.terminalErrorCode())
                    : "AGENT_EXECUTION_FAILED";
            return new TerminalOutcomeDTO(RuntimeExecutionTerminalReason.FAILED, errorCode);
        }
        if (projector.terminalReason() == StopReason.ERROR) {
            return new TerminalOutcomeDTO(
                    RuntimeExecutionTerminalReason.FAILED, executionErrorCode(projector.terminalErrorCode()));
        }
        return new TerminalOutcomeDTO(RuntimeExecutionTerminalReason.DONE, null);
    }

    private static String executionErrorCode(String value) {
        Set<String> codes = ClawConstants.RuntimeApi.EXECUTION_ERROR_CODES;
        return value != null && codes.contains(value) ? value : "MODEL_REQUEST_FAILED";
    }

    private void commitAndRelease(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeSubscriptionsDTO subscriptions,
            TerminalPlanDTO plan,
            boolean publish) {
        CommittedEventDTO committed = commitTerminal(execution, plan);
        Throwable failure = plan.executionFailure();
        if (publish) {
            failure = emitTerminal(execution, committed, failure);
        }
        failure = release(holder, execution, subscriptions, failure);
        if (publish) {
            finishOutput(execution, failure);
        } else {
            completeExecution(execution, failure);
        }
    }

    private CommittedEventDTO commitTerminal(RuntimeActiveExecution execution, TerminalPlanDTO plan) {
        RuntimeEntryDTO entry = codec.sessionIdleEntry(
                execution.target().sessionId(),
                plan.eventId(),
                plan.reason().value(),
                execution.target().rootEventId(),
                plan.errorCode(),
                plan.terminalAt());
        CommittedEventDTO event = events.sessionIdle(
                entry,
                plan.eventId(),
                plan.reason().value(),
                execution.target().rootEventId(),
                plan.errorCode(),
                plan.locale());
        persistence.commitTerminal(execution.target(), entry, event, plan.reason(), plan.terminalAt());
        return event;
    }

    private Throwable emitTerminal(
            RuntimeActiveExecution execution, CommittedEventDTO event, Throwable executionFailure) {
        try {
            execution.output().emit(() -> encoder.committed(event));
            return executionFailure;
        } catch (RuntimeException outputError) {
            return combineFailures(executionFailure, outputError);
        }
    }

    private void deferTerminal(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeSubscriptionsDTO subscriptions,
            TerminalPlanDTO plan,
            RuntimeException terminalError) {
        execution.markTerminalRetryPending();
        Throwable observed = suspendForRetry(execution, subscriptions, terminalError);
        logTerminalRetry(execution, observed);
        scheduleRetry(holder, execution, plan);
    }

    private static Throwable suspendForRetry(
            RuntimeActiveExecution execution, RuntimeSubscriptionsDTO subscriptions, Throwable failure) {
        Throwable result = failure;
        try {
            subscriptions.unsubscribe();
        } catch (RuntimeException unsubscribeError) {
            result = combineFailures(result, unsubscribeError);
        }
        execution.cancelTimeoutTask();
        try {
            execution.output().complete();
        } catch (RuntimeException outputError) {
            result = combineFailures(result, outputError);
        }
        return result;
    }

    private void scheduleRetry(RuntimeSessionHolder holder, RuntimeActiveExecution execution, TerminalPlanDTO plan) {
        try {
            terminalRetries.schedule(
                    () -> retryTerminal(holder, execution, plan), properties.getTerminalRetryInterval());
        } catch (RuntimeException schedulingError) {
            logTerminalRetry(execution, schedulingError);
        }
    }

    private void retryTerminal(RuntimeSessionHolder holder, RuntimeActiveExecution execution, TerminalPlanDTO plan) {
        try {
            engines.withOperationLock(
                    holder.sessionId(),
                    () -> commitAndRelease(holder, execution, RuntimeSubscriptionsDTO.empty(), plan, false));
        } catch (RuntimeException terminalError) {
            logTerminalRetry(execution, terminalError);
            scheduleRetry(holder, execution, plan);
        }
    }

    private TerminalPlanDTO terminalPlan(
            RuntimeActiveExecution execution,
            RuntimeExecutionTerminalReason reason,
            String errorCode,
            Locale locale,
            Throwable executionFailure) {
        return new TerminalPlanDTO(reason, errorCode, locale, executionFailure, ids.nextId(), now());
    }

    private Throwable release(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeSubscriptionsDTO subscriptions,
            Throwable failure) {
        Throwable result = failure;
        try {
            subscriptions.unsubscribe();
        } catch (RuntimeException error) {
            result = combineFailures(result, error);
        }
        try {
            engines.complete(holder, execution);
        } catch (RuntimeException error) {
            result = combineFailures(result, error);
        }
        return result;
    }

    private static void finishOutput(RuntimeActiveExecution execution, Throwable failure) {
        Throwable result = failure;
        try {
            execution.output().complete();
        } catch (RuntimeException error) {
            result = combineFailures(result, error);
        }
        completeExecution(execution, result);
    }

    private static void completeExecution(RuntimeActiveExecution execution, Throwable failure) {
        execution.complete(failure);
        if (failure != null) {
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.events.v2.execute")
                    .addKeyValue("errorCode", RuntimeErrorCode.SESSION_EXECUTION_FAILED.name())
                    .addKeyValue("sessionId", execution.target().sessionId())
                    .setCause(failure)
                    .log("CampusClaw v2 execution failed");
        }
    }

    private static void logTerminalRetry(RuntimeActiveExecution execution, Throwable failure) {
        LOGGER.atWarn()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.events.v2.terminal.retry")
                .addKeyValue("errorCode", RuntimeErrorCode.SESSION_EXECUTION_FAILED.name())
                .addKeyValue("sessionId", execution.target().sessionId())
                .setCause(failure)
                .log("CampusClaw v2 terminal commit will be retried");
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

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record TerminalOutcomeDTO(RuntimeExecutionTerminalReason reason, String errorCode) {}

    private record TerminalPlanDTO(
            RuntimeExecutionTerminalReason reason,
            String errorCode,
            Locale locale,
            Throwable executionFailure,
            String eventId,
            OffsetDateTime terminalAt) {}

    private record RuntimeSubscriptionsDTO(Runnable agent, Runnable compaction) {
        private static RuntimeSubscriptionsDTO empty() {
            return new RuntimeSubscriptionsDTO(() -> {}, () -> {});
        }

        private void unsubscribe() {
            agent.run();
            compaction.run();
        }
    }
}
