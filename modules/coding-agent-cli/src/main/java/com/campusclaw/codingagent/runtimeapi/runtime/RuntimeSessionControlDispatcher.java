/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionControlRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 将持久化控制信号交付给本机原执行实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeSessionControlDispatcher implements RuntimeLocalControlDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeSessionControlDispatcher.class);

    private final RuntimeSessionEngineRegistry engines;

    private final RuntimeExecutionControlRepository controls;

    private final Clock clock;

    public RuntimeSessionControlDispatcher(
            RuntimeSessionEngineRegistry engines, RuntimeExecutionControlRepository controls, Clock clock) {
        this.engines = engines;
        this.controls = controls;
        this.clock = clock;
    }

    @Override
    public List<ExecutionTargetDTO> activeTargets(int limit) {
        return engines.activeTargets(limit);
    }

    @Override
    public boolean dispatchStop(ExecutionTargetDTO target) {
        return engines.withOperationLock(target.sessionId(), () -> dispatchStopLocked(target));
    }

    @Override
    public boolean dispatchConfirmation(ExecutionTargetDTO confirmingTarget, String toolCallId) {
        return engines.withOperationLock(
                confirmingTarget.sessionId(), () -> dispatchConfirmationLocked(confirmingTarget, toolCallId));
    }

    public Optional<ExecutionTargetDTO> stageConfirmation(
            ExecutionTargetDTO resumedTarget, String toolCallId, RuntimeEventOutput output) {
        return engines.withOperationLock(
                resumedTarget.sessionId(), () -> stageConfirmationLocked(resumedTarget, toolCallId, output));
    }

    private boolean dispatchStopLocked(ExecutionTargetDTO target) {
        Optional<RuntimeSessionHolder> holder = engines.find(target.sessionId());
        Optional<RuntimeActiveExecution> active = exactExecution(holder, target);
        if (holder.isEmpty() || active.isEmpty()) {
            return false;
        }
        RuntimeActiveExecution execution = active.get();
        execution.requestAbort();
        try {
            holder.get().abort();
            execution.cancelToolConfirmation(new CancellationException("runtime execution was interrupted"));
            return true;
        } catch (RuntimeException error) {
            logDispatchFailure("runtime.execution.interrupt", target, error);
            return false;
        }
    }

    private boolean dispatchConfirmationLocked(ExecutionTargetDTO confirmingTarget, String toolCallId) {
        Optional<RuntimeActiveExecution> active =
                exactExecution(engines.find(confirmingTarget.sessionId()), confirmingTarget);
        if (active.isEmpty()) {
            return false;
        }
        Optional<ToolConfirmationDecisionDTO> decision;
        try {
            decision = active.get()
                    .claimAndResumeToolConfirmation(
                            confirmingTarget,
                            toolCallId,
                            () -> controls.claimConfirmation(confirmingTarget, toolCallId, now()));
        } catch (RuntimeException error) {
            logDispatchFailure("runtime.execution.confirmation", confirmingTarget, error);
            return false;
        }
        decision.ifPresent(this::acknowledgeConfirmation);
        return decision.isPresent();
    }

    private Optional<ExecutionTargetDTO> stageConfirmationLocked(
            ExecutionTargetDTO resumedTarget, String toolCallId, RuntimeEventOutput output) {
        Optional<RuntimeActiveExecution> active = sameExecution(engines.find(resumedTarget.sessionId()), resumedTarget);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        ExecutionTargetDTO confirmingTarget = active.get().target();
        boolean staged = active.get().stageContinuationOutput(resumedTarget, toolCallId, output);
        return staged ? Optional.of(confirmingTarget) : Optional.empty();
    }

    private static Optional<RuntimeActiveExecution> exactExecution(
            Optional<RuntimeSessionHolder> holder, ExecutionTargetDTO target) {
        return holder.flatMap(RuntimeSessionHolder::activeExecution)
                .filter(execution ->
                        execution.assignedTarget().filter(target::equals).isPresent());
    }

    private static Optional<RuntimeActiveExecution> sameExecution(
            Optional<RuntimeSessionHolder> holder, ExecutionTargetDTO target) {
        return holder.flatMap(RuntimeSessionHolder::activeExecution).filter(execution -> execution
                .assignedTarget()
                .filter(assigned -> sameExecution(assigned, target))
                .isPresent());
    }

    private static boolean sameExecution(ExecutionTargetDTO first, ExecutionTargetDTO second) {
        return first.sessionId().equals(second.sessionId())
                && first.executionId().equals(second.executionId())
                && first.rootEventId().equals(second.rootEventId());
    }

    private void acknowledgeConfirmation(ToolConfirmationDecisionDTO decision) {
        try {
            if (!controls.acknowledgeConfirmation(decision, now())) {
                logConfirmationAcknowledgementFailure(decision, null);
            }
        } catch (RuntimeException error) {
            logConfirmationAcknowledgementFailure(decision, error);
        }
    }

    private void logDispatchFailure(String operation, ExecutionTargetDTO target, RuntimeException error) {
        LOGGER.atWarn()
                .addKeyValue("operation", operation)
                .addKeyValue("sessionId", target.sessionId())
                .addKeyValue("executionId", target.executionId())
                .setCause(error)
                .log("Runtime control delivery will be retried by polling");
    }

    private void logConfirmationAcknowledgementFailure(ToolConfirmationDecisionDTO decision, RuntimeException error) {
        LOGGER.atWarn()
                .addKeyValue("operation", "runtime.execution.confirmation.acknowledge")
                .addKeyValue("sessionId", decision.getSessionId())
                .addKeyValue("executionId", decision.getExecutionId())
                .setCause(error)
                .log("Runtime confirmation acknowledgement failed after local delivery");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
