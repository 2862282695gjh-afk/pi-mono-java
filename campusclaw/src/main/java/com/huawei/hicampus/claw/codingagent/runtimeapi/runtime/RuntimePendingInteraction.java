/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.PlanProposalSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.PlanReviewDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionException;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

/**
 * 收敛单个根执行当前唯一的权限、计划或用户问题等待。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimePendingInteraction {
    /**
     * 当前等待的明确业务类型。
     */
    public enum Kind {
        TOOL_PERMISSION,
        PLAN_REVIEW,
        USER_QUESTION
    }

    /**
     * 已从持久化层领取但尚未唤醒工具线程的交接对象。
     *
     * @param decision 已领取决定
     * @param waiting 待唤醒 Future
     * @param <T> 决定类型
     */
    public record Claimed<T>(T decision, CompletableFuture<T> waiting) {
        public void complete() {
            waiting.complete(decision);
        }
    }

    private Kind kind;

    private String toolCallId;

    private String targetEventId;

    private PlanProposalSnapshotDTO plan;

    private QuestionRequestSnapshotDTO question;

    private CompletableFuture<?> future;

    private ExecutionTargetDTO continuationTarget;

    private RuntimeEventOutput continuationOutput;

    private ExecutionTargetDTO waitingTarget;

    public synchronized CompletableFuture<ToolConfirmationDecisionDTO> beginTool(String callId) {
        requireAvailable();
        kind = Kind.TOOL_PERMISSION;
        toolCallId = requireText(callId, "toolCallId");
        var created = new CompletableFuture<ToolConfirmationDecisionDTO>();
        future = created;
        return created;
    }

    public synchronized CompletableFuture<PlanReviewDecisionDTO> beginPlan(PlanProposalSnapshotDTO proposal) {
        requireAvailable();
        java.util.Objects.requireNonNull(proposal, "proposal");
        kind = Kind.PLAN_REVIEW;
        toolCallId = requireText(proposal.toolCallId(), "toolCallId");
        targetEventId = requireText(proposal.planEventId(), "planEventId");
        plan = proposal;
        waitingTarget = java.util.Objects.requireNonNull(proposal.target(), "target");
        var created = new CompletableFuture<PlanReviewDecisionDTO>();
        future = created;
        return created;
    }

    public synchronized CompletableFuture<QuestionAnswerDecisionDTO> beginQuestion(
            String callId, String eventId, QuestionRequestSnapshotDTO snapshot) {
        requireAvailable();
        kind = Kind.USER_QUESTION;
        toolCallId = requireText(callId, "toolCallId");
        targetEventId = requireText(eventId, "questionEventId");
        question = java.util.Objects.requireNonNull(snapshot, "question");
        var created = new CompletableFuture<QuestionAnswerDecisionDTO>();
        future = created;
        return created;
    }

    public PlanReviewDecisionDTO awaitPlan(String callId, CancellationToken signal) throws Exception {
        CompletableFuture<PlanReviewDecisionDTO> waiting;
        synchronized (this) {
            if (!matchesCall(Kind.PLAN_REVIEW, callId) || plan == null) {
                throw new com.huawei.hicampus.claw.codingagent.tool.plan.PlanReviewException("PLAN_REVIEW_UNAVAILABLE");
            }
            waiting = castFuture();
        }
        signal.onCancel(() -> waiting.completeExceptionally(
                new java.util.concurrent.CancellationException("Plan review was cancelled")));
        PlanReviewDecisionDTO decision = waiting.get();
        clearIfSame(waiting);
        return decision;
    }

    public QuestionAnswerResultDTO awaitQuestion(String callId, CancellationToken signal) throws Exception {
        CompletableFuture<QuestionAnswerDecisionDTO> waiting;
        synchronized (this) {
            if (!matchesCall(Kind.USER_QUESTION, callId)) {
                throw unavailable();
            }
            waiting = castFuture();
        }
        signal.onCancel(() -> waiting.completeExceptionally(
                new java.util.concurrent.CancellationException("User question was cancelled")));
        QuestionAnswerDecisionDTO decision = waiting.get();
        clearIfSame(waiting);
        return java.util.Objects.requireNonNull(decision.getAnswers(), "question answers");
    }

    public synchronized boolean stage(
            Kind expected,
            ExecutionTargetDTO current,
            ExecutionTargetDTO resumed,
            String eventOrCallId,
            RuntimeEventOutput output) {
        if (!matchesIdentity(expected, eventOrCallId)
                || !sameExecution(current, resumed)
                || (waitingTarget != null && !waitingTarget.equals(current))) {
            return false;
        }
        waitingTarget = current;
        continuationTarget = resumed;
        continuationOutput = java.util.Objects.requireNonNull(output, "continuationOutput");
        return true;
    }

    public Optional<Claimed<ToolConfirmationDecisionDTO>> claimTool(
            ExecutionTargetDTO current, String callId, Supplier<Optional<ToolConfirmationDecisionDTO>> claim) {
        return claim(current, Kind.TOOL_PERMISSION, callId, claim, this::matchesToolDecision);
    }

    public Optional<Claimed<PlanReviewDecisionDTO>> claimPlan(
            ExecutionTargetDTO current, String eventId, Supplier<Optional<PlanReviewDecisionDTO>> claim) {
        return claim(current, Kind.PLAN_REVIEW, eventId, claim, this::matchesPlanDecision);
    }

    public Optional<Claimed<QuestionAnswerDecisionDTO>> claimQuestion(
            ExecutionTargetDTO current, String eventId, Supplier<Optional<QuestionAnswerDecisionDTO>> claim) {
        return claim(current, Kind.USER_QUESTION, eventId, claim, this::matchesQuestionDecision);
    }

    public synchronized RuntimeEventOutput applyContinuation(ExecutionTargetDTO resumed) {
        return resumed.equals(continuationTarget) ? continuationOutput : RuntimeEventOutput.persistenceOnly();
    }

    public synchronized String targetEventId(Kind expected) {
        return kind == expected ? targetEventId : null;
    }

    public synchronized String identity(Kind expected) {
        if (kind != expected) {
            return null;
        }
        return expected == Kind.TOOL_PERMISSION ? toolCallId : targetEventId;
    }

    public synchronized QuestionRequestSnapshotDTO question(String callId) {
        return matchesCall(Kind.USER_QUESTION, callId) ? question : null;
    }

    public synchronized void clearTool() {
        if (kind == Kind.TOOL_PERMISSION) {
            clear();
        }
    }

    public synchronized CompletableFuture<?> cancel(Kind expected) {
        if (kind != expected || future == null || (future.isDone() && !future.isCompletedExceptionally())) {
            return null;
        }
        CompletableFuture<?> waiting = future;
        clear();
        return waiting;
    }

    public synchronized void clearContinuation() {
        continuationTarget = null;
        continuationOutput = null;
    }

    private <T> Optional<Claimed<T>> claim(
            ExecutionTargetDTO current,
            Kind expected,
            String identity,
            Supplier<Optional<T>> supplier,
            java.util.function.Predicate<T> validator) {
        T decision;
        CompletableFuture<T> waiting;
        synchronized (this) {
            if (!matchesTarget(current, expected, identity)) {
                return Optional.empty();
            }
            Optional<T> claimed = java.util.Objects.requireNonNull(supplier.get(), "claimed interaction");
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            decision = claimed.get();
            if (!validator.test(decision)) {
                throw new IllegalStateException("claimed interaction does not match the pending target");
            }
            waiting = castFuture();
        }
        return Optional.of(new Claimed<>(decision, waiting));
    }

    private synchronized boolean matchesToolDecision(ToolConfirmationDecisionDTO decision) {
        return decision != null
                && toolCallId.equals(decision.getToolCallId())
                && matchesPrevious(decision.getSessionId(), decision.getExecutionId(), decision.getPreviousSegmentId());
    }

    private synchronized boolean matchesPlanDecision(PlanReviewDecisionDTO decision) {
        return decision != null
                && targetEventId.equals(decision.getTargetEventId())
                && toolCallId.equals(decision.getToolCallId())
                && matchesPrevious(decision.getSessionId(), decision.getExecutionId(), decision.getPreviousSegmentId());
    }

    private synchronized boolean matchesQuestionDecision(QuestionAnswerDecisionDTO decision) {
        return decision != null
                && targetEventId.equals(decision.getTargetEventId())
                && toolCallId.equals(decision.getToolCallId())
                && matchesPrevious(decision.getSessionId(), decision.getExecutionId(), decision.getPreviousSegmentId());
    }

    private boolean matchesPrevious(String sessionId, String executionId, String segmentId) {
        return waitingTarget != null
                && waitingTarget.sessionId().equals(sessionId)
                && waitingTarget.executionId().equals(executionId)
                && waitingTarget.segmentId().equals(segmentId);
    }

    private synchronized boolean matchesTarget(ExecutionTargetDTO current, Kind expected, String identity) {
        if (current == null || !matchesIdentity(expected, identity)) {
            return false;
        }
        if (waitingTarget == null) {
            waitingTarget = current;
        }
        return current.equals(waitingTarget);
    }

    private synchronized boolean matchesIdentity(Kind expected, String identity) {
        String pendingIdentity = expected == Kind.TOOL_PERMISSION ? toolCallId : targetEventId;
        return kind == expected && future != null && pendingIdentity != null && pendingIdentity.equals(identity);
    }

    private synchronized boolean matchesCall(Kind expected, String callId) {
        return kind == expected && future != null && toolCallId != null && toolCallId.equals(callId);
    }

    private static boolean sameExecution(ExecutionTargetDTO first, ExecutionTargetDTO second) {
        return first != null
                && second != null
                && first.sessionId().equals(second.sessionId())
                && first.executionId().equals(second.executionId())
                && first.rootEventId().equals(second.rootEventId());
    }

    private synchronized void clearIfSame(CompletableFuture<?> waiting) {
        if (future == waiting) {
            clear();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> castFuture() {
        return (CompletableFuture<T>) future;
    }

    private void requireAvailable() {
        if (future != null) {
            throw unavailable();
        }
    }

    private void clear() {
        kind = null;
        toolCallId = null;
        targetEventId = null;
        plan = null;
        question = null;
        future = null;
        waitingTarget = null;
        clearContinuation();
    }

    private static UserQuestionException unavailable() {
        return new UserQuestionException(ClawConstants.RuntimeApi.USER_QUESTION_UNAVAILABLE_ERROR_CODE);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
