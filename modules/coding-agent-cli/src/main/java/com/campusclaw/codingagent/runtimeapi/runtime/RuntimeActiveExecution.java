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
import com.campusclaw.codingagent.runtimeapi.dto.QuestionAnswerDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.plan.PlanReviewException;
import com.campusclaw.codingagent.tool.plan.PlanReviewPort;
import com.campusclaw.codingagent.tool.plan.PlanReviewResultDTO;
import com.campusclaw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.campusclaw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.campusclaw.codingagent.tool.question.UserQuestionPort;
import com.campusclaw.common.constant.ClawConstants;

/**
 * 单个 Session 当前唯一活动执行的进程内句柄。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeActiveExecution implements PlanReviewPort, UserQuestionPort {
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

    private final RuntimePendingInteraction pending = new RuntimePendingInteraction();

    private String mode = ClawConstants.Agent.MODE_BUILD;

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
        try {
            return pending.beginPlan(toolCallId, planEventId, plan);
        } catch (com.campusclaw.codingagent.tool.question.UserQuestionException error) {
            throw new PlanReviewException("PLAN_REVIEW_UNAVAILABLE");
        }
    }

    public synchronized CompletableFuture<QuestionAnswerDecisionDTO> beginQuestion(
            String toolCallId, String questionEventId, QuestionRequestSnapshotDTO snapshot) {
        return pending.beginQuestion(toolCallId, questionEventId, snapshot);
    }

    @Override
    public PlanReviewResultDTO await(String toolCallId, String plan) throws Exception {
        PlanReviewDecisionDTO decision = pending.awaitPlan(toolCallId, plan);
        return new PlanReviewResultDTO(decision.getResult().isApproved(), decision.getFeedback());
    }

    @Override
    public QuestionAnswerResultDTO await(String toolCallId, com.campusclaw.agent.tool.CancellationToken signal)
            throws Exception {
        return pending.awaitQuestion(toolCallId, signal);
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
                        .equals(context.toolCall().name())
                || BuiltInToolName.ASK_USER_QUESTION
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
        return pending.beginTool(toolCallId);
    }

    public synchronized boolean stageContinuationOutput(ExecutionTargetDTO value, RuntimeEventOutput valueOutput) {
        return pending.stage(
                RuntimePendingInteraction.Kind.TOOL_PERMISSION, target, value, pendingToolCallId(), valueOutput);
    }

    public synchronized boolean stageContinuationOutput(
            ExecutionTargetDTO value, String toolCallId, RuntimeEventOutput valueOutput) {
        return pending.stage(RuntimePendingInteraction.Kind.TOOL_PERMISSION, target, value, toolCallId, valueOutput);
    }

    public synchronized boolean stagePlanContinuationOutput(
            ExecutionTargetDTO value, String planEventId, RuntimeEventOutput valueOutput) {
        return pending.stage(RuntimePendingInteraction.Kind.PLAN_REVIEW, target, value, planEventId, valueOutput);
    }

    public synchronized boolean stageQuestionContinuationOutput(
            ExecutionTargetDTO value, String questionEventId, RuntimeEventOutput valueOutput) {
        return pending.stage(RuntimePendingInteraction.Kind.USER_QUESTION, target, value, questionEventId, valueOutput);
    }

    public boolean resumeToolConfirmation(ToolConfirmationDecisionDTO decision) {
        Optional<RuntimePendingInteraction.Claimed<ToolConfirmationDecisionDTO>> claimed;
        synchronized (this) {
            String callId = decision == null ? null : decision.getToolCallId();
            claimed = pending.claimTool(target, callId, () -> Optional.ofNullable(decision));
            if (claimed.isEmpty()) {
                return false;
            }
            applyToolDecision(claimed.get());
        }
        return true;
    }

    public Optional<ToolConfirmationDecisionDTO> claimAndResumeToolConfirmation(
            ExecutionTargetDTO confirmingTarget,
            String toolCallId,
            Supplier<Optional<ToolConfirmationDecisionDTO>> claim) {
        Optional<RuntimePendingInteraction.Claimed<ToolConfirmationDecisionDTO>> claimed;
        synchronized (this) {
            claimed = pending.claimTool(confirmingTarget, toolCallId, claim);
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            applyToolDecision(claimed.get());
        }
        return claimed.map(RuntimePendingInteraction.Claimed::decision);
    }

    public void cancelToolConfirmation(Throwable failure) {
        CompletableFuture<?> future = pending.cancel(RuntimePendingInteraction.Kind.TOOL_PERMISSION);
        if (future != null) {
            future.completeExceptionally(failure);
        }
    }

    public Optional<PlanReviewDecisionDTO> claimAndResumePlanReview(
            ExecutionTargetDTO confirmingTarget, String planEventId, Supplier<Optional<PlanReviewDecisionDTO>> claim) {
        Optional<RuntimePendingInteraction.Claimed<PlanReviewDecisionDTO>> claimed;
        synchronized (this) {
            claimed = pending.claimPlan(confirmingTarget, planEventId, claim);
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            PlanReviewDecisionDTO decision = claimed.get().decision();
            target = planDecisionTarget(decision);
            output = pending.applyContinuation(target);
            applyPlanMode(decision);
            claimed.get().complete();
        }
        return claimed.map(RuntimePendingInteraction.Claimed::decision);
    }

    public Optional<QuestionAnswerDecisionDTO> claimAndResumeQuestionAnswer(
            ExecutionTargetDTO confirmingTarget,
            String questionEventId,
            Supplier<Optional<QuestionAnswerDecisionDTO>> claim) {
        Optional<RuntimePendingInteraction.Claimed<QuestionAnswerDecisionDTO>> claimed;
        synchronized (this) {
            claimed = pending.claimQuestion(confirmingTarget, questionEventId, claim);
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            target = questionDecisionTarget(claimed.get().decision());
            output = pending.applyContinuation(target);
            claimed.get().complete();
        }
        return claimed.map(RuntimePendingInteraction.Claimed::decision);
    }

    public void cancelPlanReview(Throwable failure) {
        CompletableFuture<?> future = pending.cancel(RuntimePendingInteraction.Kind.PLAN_REVIEW);
        if (future != null) {
            future.completeExceptionally(failure);
        }
    }

    public void cancelQuestion(Throwable failure) {
        CompletableFuture<?> future = pending.cancel(RuntimePendingInteraction.Kind.USER_QUESTION);
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
            output = pending.applyContinuation(value);
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

    private ExecutionTargetDTO decisionTarget(ToolConfirmationDecisionDTO decision) {
        return new ExecutionTargetDTO(
                decision.getSessionId(),
                decision.getExecutionId(),
                target.rootEventId(),
                requireText(decision.getSegmentId(), "segmentId"));
    }

    private ExecutionTargetDTO planDecisionTarget(PlanReviewDecisionDTO decision) {
        return new ExecutionTargetDTO(
                decision.getSessionId(),
                decision.getExecutionId(),
                target.rootEventId(),
                requireText(decision.getSegmentId(), "segmentId"));
    }

    private ExecutionTargetDTO questionDecisionTarget(QuestionAnswerDecisionDTO decision) {
        return new ExecutionTargetDTO(
                decision.getSessionId(),
                decision.getExecutionId(),
                target.rootEventId(),
                requireText(decision.getSegmentId(), "segmentId"));
    }

    private void applyToolDecision(RuntimePendingInteraction.Claimed<ToolConfirmationDecisionDTO> claimed) {
        ToolConfirmationDecisionDTO decision = claimed.decision();
        ExecutionTargetDTO resumed = decisionTarget(decision);
        output = pending.applyContinuation(resumed);
        target = resumed;
        pending.clearTool();
        claimed.complete();
    }

    private void applyPlanMode(PlanReviewDecisionDTO decision) {
        if (decision.getResult().isApproved()) {
            mode = ClawConstants.Agent.MODE_BUILD;
            modeApplier.run();
        }
    }

    private String pendingToolCallId() {
        return pending.identity(RuntimePendingInteraction.Kind.TOOL_PERMISSION);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
