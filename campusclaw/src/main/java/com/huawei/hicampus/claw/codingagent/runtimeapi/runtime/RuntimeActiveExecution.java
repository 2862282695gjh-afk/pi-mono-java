/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallHandler;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;

/**
 * 单个 Session 当前唯一活动执行的进程内句柄。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeActiveExecution {
    private RuntimeEventOutput output;

    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private boolean abortRequested;

    private boolean timedOut;

    private Future<?> timeoutTask;

    private String runId;

    private ExecutionTargetDTO target;

    private boolean terminalFinalizationStarted;

    private boolean terminalRetryPending;

    private RuntimeToolPermissionPolicy toolPermissions = RuntimeToolPermissionPolicy.builtInsOnly();

    private BeforeToolCallHandler confirmationHandler;

    private String pendingToolCallId;

    private CompletableFuture<ToolConfirmationDecisionDTO> pendingConfirmation;

    private ExecutionTargetDTO continuationTarget;

    private RuntimeEventOutput continuationOutput;

    public RuntimeActiveExecution(RuntimeEventOutput output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    public synchronized RuntimeEventOutput output() {
        return output;
    }

    public synchronized void bindToolPermissions(RuntimeToolPermissionPolicy value) {
        toolPermissions = Objects.requireNonNull(value, "toolPermissions");
    }

    public synchronized RuntimeToolPermissionPolicy.Decision toolPermission(ToolCall call) {
        return toolPermissions.decide(call);
    }

    public synchronized void installConfirmationHandler(BeforeToolCallHandler handler) {
        confirmationHandler = Objects.requireNonNull(handler, "confirmationHandler");
    }

    public BeforeToolCallResult beforeToolCall(BeforeToolCallContext context) throws Exception {
        RuntimeToolPermissionPolicy.Decision decision = toolPermission(context.toolCall());
        if (decision == RuntimeToolPermissionPolicy.Decision.ALLOW) {
            return BeforeToolCallResult.allow();
        }
        if (decision == RuntimeToolPermissionPolicy.Decision.DENY) {
            throw new RuntimeToolCallDeniedException();
        }
        BeforeToolCallHandler handler = requireConfirmationHandler();
        return handler.handle(context);
    }

    public synchronized CompletableFuture<ToolConfirmationDecisionDTO> beginToolConfirmation(String toolCallId) {
        if (pendingConfirmation != null) {
            throw new IllegalStateException("another tool confirmation is already pending");
        }
        pendingToolCallId = requireText(toolCallId, "toolCallId");
        pendingConfirmation = new CompletableFuture<>();
        return pendingConfirmation;
    }

    public synchronized boolean stageContinuationOutput(ExecutionTargetDTO value, RuntimeEventOutput valueOutput) {
        return stageContinuationOutput(value, pendingToolCallId, valueOutput);
    }

    public synchronized boolean stageContinuationOutput(
            ExecutionTargetDTO value, String toolCallId, RuntimeEventOutput valueOutput) {
        if (!matchesExecution(value) || pendingConfirmation == null || !pendingToolCallId.equals(toolCallId)) {
            return false;
        }
        continuationTarget = value;
        continuationOutput = Objects.requireNonNull(valueOutput, "continuationOutput");
        return true;
    }

    public boolean resumeToolConfirmation(ToolConfirmationDecisionDTO decision) {
        CompletableFuture<ToolConfirmationDecisionDTO> future;
        synchronized (this) {
            if (!matchesPendingDecision(decision)) {
                return false;
            }
            future = applyConfirmationDecision(decision);
        }
        return future.complete(decision);
    }

    public Optional<ToolConfirmationDecisionDTO> claimAndResumeToolConfirmation(
            ExecutionTargetDTO confirmingTarget,
            String toolCallId,
            Supplier<Optional<ToolConfirmationDecisionDTO>> claim) {
        ToolConfirmationDecisionDTO decision;
        CompletableFuture<ToolConfirmationDecisionDTO> future;
        synchronized (this) {
            if (!matchesPendingTarget(confirmingTarget, toolCallId)) {
                return Optional.empty();
            }
            Optional<ToolConfirmationDecisionDTO> claimed = Objects.requireNonNull(claim.get(), "claimed decision");
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            decision = claimed.get();
            if (!matchesPendingDecision(decision)) {
                throw new IllegalStateException("claimed confirmation does not match the pending tool call");
            }
            future = applyConfirmationDecision(decision);
        }
        future.complete(decision);
        return Optional.of(decision);
    }

    public void cancelToolConfirmation(Throwable failure) {
        CompletableFuture<ToolConfirmationDecisionDTO> future;
        synchronized (this) {
            future = pendingConfirmation;
            clearPendingConfirmation();
        }
        if (future != null) {
            future.completeExceptionally(failure);
        }
    }

    public synchronized void beginRun(String value) {
        if (runId != null) {
            throw new IllegalStateException("execution run id is already assigned");
        }
        runId = value;
    }

    public synchronized String runId() {
        if (runId == null) {
            throw new IllegalStateException("execution run id is not assigned");
        }
        return runId;
    }

    public synchronized void bindTarget(ExecutionTargetDTO value) {
        if (target != null) {
            throw new IllegalStateException("execution target is already assigned");
        }
        target = Objects.requireNonNull(value, "target");
    }

    public synchronized ExecutionTargetDTO target() {
        if (target == null) {
            throw new IllegalStateException("execution target is not assigned");
        }
        return target;
    }

    public synchronized Optional<ExecutionTargetDTO> assignedTarget() {
        return Optional.ofNullable(target);
    }

    public synchronized boolean beginTerminalFinalization() {
        if (terminalFinalizationStarted) {
            return false;
        }
        terminalFinalizationStarted = true;
        return true;
    }

    public synchronized void markTerminalRetryPending() {
        terminalRetryPending = true;
    }

    public synchronized boolean terminalRetryPending() {
        return terminalRetryPending;
    }

    public synchronized void requestAbort() {
        abortRequested = true;
    }

    public synchronized void requestTimeout() {
        timedOut = true;
    }

    public synchronized boolean abortRequested() {
        return abortRequested;
    }

    public synchronized boolean timedOut() {
        return timedOut;
    }

    public synchronized void setTimeoutTask(Future<?> task) {
        if (completion.isDone()) {
            task.cancel(false);
        } else {
            timeoutTask = task;
        }
    }

    public synchronized void cancelTimeoutTask() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public synchronized void complete(Throwable failure) {
        cancelTimeoutTask();
        terminalRetryPending = false;
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
    }

    private synchronized BeforeToolCallHandler requireConfirmationHandler() {
        if (confirmationHandler == null) {
            throw new IllegalStateException("tool confirmation handler is not installed");
        }
        return confirmationHandler;
    }

    private boolean matchesExecution(ExecutionTargetDTO value) {
        return value != null
                && target != null
                && target.sessionId().equals(value.sessionId())
                && target.executionId().equals(value.executionId())
                && target.rootEventId().equals(value.rootEventId());
    }

    private boolean matchesPendingDecision(ToolConfirmationDecisionDTO decision) {
        return decision != null
                && pendingConfirmation != null
                && target.sessionId().equals(decision.getSessionId())
                && target.executionId().equals(decision.getExecutionId())
                && target.segmentId().equals(decision.getPreviousSegmentId())
                && pendingToolCallId.equals(decision.getToolCallId());
    }

    private boolean matchesPendingTarget(ExecutionTargetDTO value, String toolCallId) {
        return pendingConfirmation != null && target.equals(value) && pendingToolCallId.equals(toolCallId);
    }

    private CompletableFuture<ToolConfirmationDecisionDTO> applyConfirmationDecision(
            ToolConfirmationDecisionDTO decision) {
        ExecutionTargetDTO resumed = decisionTarget(decision);
        output = resumed.equals(continuationTarget) ? continuationOutput : RuntimeEventOutput.persistenceOnly();
        target = resumed;
        CompletableFuture<ToolConfirmationDecisionDTO> future = pendingConfirmation;
        clearPendingConfirmation();
        return future;
    }

    private ExecutionTargetDTO decisionTarget(ToolConfirmationDecisionDTO decision) {
        return new ExecutionTargetDTO(
                decision.getSessionId(),
                decision.getExecutionId(),
                target.rootEventId(),
                requireText(decision.getSegmentId(), "segmentId"));
    }

    private void clearPendingConfirmation() {
        pendingToolCallId = null;
        pendingConfirmation = null;
        continuationTarget = null;
        continuationOutput = null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
