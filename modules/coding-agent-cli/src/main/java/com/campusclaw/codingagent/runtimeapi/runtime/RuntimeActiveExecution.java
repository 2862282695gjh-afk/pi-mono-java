/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.BeforeToolCallHandler;
import com.campusclaw.agent.tool.BeforeToolCallResult;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanReviewDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.plan.PlanReviewException;
import com.campusclaw.codingagent.tool.plan.PlanReviewPort;
import com.campusclaw.codingagent.tool.plan.PlanReviewResultDTO;
import com.campusclaw.common.constant.ClawConstants;

/**
 * 单个 Session 当前唯一活动执行的进程内句柄。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeActiveExecution implements PlanReviewPort {
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

    private String mode = ClawConstants.Agent.MODE_BUILD;

    private String pendingPlanEventId;

    private String pendingPlan;

    private CompletableFuture<PlanReviewDecisionDTO> pendingPlanReview;

    private Runnable modeApplier = () -> {};

    public RuntimeActiveExecution(RuntimeEventOutput output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    public synchronized RuntimeEventOutput output() {
        return output;
    }

    public synchronized void applyMode(String value) {
        if (!ClawConstants.Agent.MODES.contains(value)) {
            throw new IllegalArgumentException("runtime execution mode is invalid");
        }
        mode = value;
    }

    public synchronized String mode() {
        return mode;
    }

    public synchronized void installModeApplier(Runnable value) {
        modeApplier = Objects.requireNonNull(value, "modeApplier");
    }

    public synchronized CompletableFuture<PlanReviewDecisionDTO> beginPlanReview(
            String toolCallId, String planEventId, String plan) {
        if (!ClawConstants.Agent.MODE_PLAN.equals(mode)) {
            throw new PlanReviewException("PLAN_MODE_INACTIVE");
        }
        if (pendingConfirmation != null || pendingPlanReview != null) {
            throw new PlanReviewException("PLAN_REVIEW_UNAVAILABLE");
        }
        pendingToolCallId = requireText(toolCallId, "toolCallId");
        pendingPlanEventId = requireText(planEventId, "planEventId");
        pendingPlan = requireText(plan, "plan");
        pendingPlanReview = new CompletableFuture<>();
        return pendingPlanReview;
    }

    @Override
    public PlanReviewResultDTO await(String toolCallId, String plan) throws Exception {
        CompletableFuture<PlanReviewDecisionDTO> future;
        synchronized (this) {
            if (pendingPlanReview == null || !pendingToolCallId.equals(toolCallId) || !pendingPlan.equals(plan)) {
                throw new PlanReviewException("PLAN_REVIEW_UNAVAILABLE");
            }
            future = pendingPlanReview;
        }
        PlanReviewDecisionDTO decision = future.get();
        synchronized (this) {
            if (pendingPlanReview == future) {
                clearPendingPlanReview();
            }
        }
        return new PlanReviewResultDTO(decision.getResult().isApproved(), decision.getFeedback());
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
        if (BuiltInToolName.EXIT_PLAN_MODE
                .externalName()
                .equals(context.toolCall().name())) {
            return requireConfirmationHandler().handle(context);
        }
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
        if (pendingConfirmation != null || pendingPlanReview != null) {
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

    public synchronized boolean stagePlanContinuationOutput(
            ExecutionTargetDTO value, String planEventId, RuntimeEventOutput valueOutput) {
        if (!matchesExecution(value) || pendingPlanReview == null || !pendingPlanEventId.equals(planEventId)) {
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

    public Optional<PlanReviewDecisionDTO> claimAndResumePlanReview(
            ExecutionTargetDTO confirmingTarget, String planEventId, Supplier<Optional<PlanReviewDecisionDTO>> claim) {
        PlanReviewDecisionDTO decision;
        CompletableFuture<PlanReviewDecisionDTO> future;
        synchronized (this) {
            if (!matchesPendingPlanTarget(confirmingTarget, planEventId)) {
                return Optional.empty();
            }
            Optional<PlanReviewDecisionDTO> claimed = Objects.requireNonNull(claim.get(), "claimed plan decision");
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            decision = claimed.get();
            requirePendingPlanDecision(decision);
            future = applyPlanDecision(decision);
        }
        future.complete(decision);
        return Optional.of(decision);
    }

    public void cancelPlanReview(Throwable failure) {
        CompletableFuture<PlanReviewDecisionDTO> future;
        synchronized (this) {
            future = pendingPlanReview;
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                return;
            }
            clearPendingPlanReview();
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

    public synchronized boolean prepareStop(ExecutionTargetDTO value) {
        if (!matchesExecution(value)) {
            return false;
        }
        if (!target.equals(value)) {
            target = value;
            if (value.equals(continuationTarget) && continuationOutput != null) {
                output = continuationOutput;
            }
        }
        abortRequested = true;
        return true;
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

    private boolean matchesPendingPlanTarget(ExecutionTargetDTO value, String planEventId) {
        return pendingPlanReview != null && target.equals(value) && pendingPlanEventId.equals(planEventId);
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

    private void requirePendingPlanDecision(PlanReviewDecisionDTO decision) {
        if (decision == null
                || !target.sessionId().equals(decision.getSessionId())
                || !target.executionId().equals(decision.getExecutionId())
                || !target.segmentId().equals(decision.getPreviousSegmentId())
                || !pendingPlanEventId.equals(decision.getTargetEventId())
                || !pendingToolCallId.equals(decision.getToolCallId())) {
            throw new IllegalStateException("claimed plan confirmation does not match the pending plan");
        }
    }

    private CompletableFuture<PlanReviewDecisionDTO> applyPlanDecision(PlanReviewDecisionDTO decision) {
        ExecutionTargetDTO resumed = new ExecutionTargetDTO(
                decision.getSessionId(),
                decision.getExecutionId(),
                target.rootEventId(),
                requireText(decision.getSegmentId(), "segmentId"));
        output = resumed.equals(continuationTarget) ? continuationOutput : RuntimeEventOutput.persistenceOnly();
        target = resumed;
        if (decision.getResult().isApproved()) {
            mode = ClawConstants.Agent.MODE_BUILD;
            modeApplier.run();
        }
        CompletableFuture<PlanReviewDecisionDTO> future = pendingPlanReview;
        continuationTarget = null;
        continuationOutput = null;
        return future;
    }

    private void clearPendingConfirmation() {
        pendingToolCallId = null;
        pendingConfirmation = null;
        continuationTarget = null;
        continuationOutput = null;
    }

    private void clearPendingPlanReview() {
        pendingToolCallId = null;
        pendingPlanEventId = null;
        pendingPlan = null;
        pendingPlanReview = null;
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
