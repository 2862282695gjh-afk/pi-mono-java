/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.PlanReviewDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.PlanConfirmationResult;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolDiscovery;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.ShowcaseConfirmationAdapter;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.junit.jupiter.api.Test;

/**
 * 活动执行的可信工具门禁和确认续跑段切换测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeActiveExecutionTest {
    @Test
    void shouldRejectUnknownMateToolWithoutCallingConfirmationHandler() {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        AtomicInteger handled = new AtomicInteger();
        execution.installConfirmationHandler(context -> {
            handled.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        BeforeToolCallContext context =
                context(new ToolCall("call-1", "CallMateTool", Map.of("tool", "unknown", "args", Map.of())));

        assertThatThrownBy(() -> execution.beforeToolCall(context)).isInstanceOf(RuntimeToolCallDeniedException.class);
        assertThat(handled).hasValue(0);
    }

    @Test
    void failedShowcaseHookShouldReleasePreparedSnapshotForNextQuestion() throws Exception {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        RuntimeToolPermissionPolicy permissions = mock(RuntimeToolPermissionPolicy.class);
        MateToolDiscovery discovery = mock(MateToolDiscovery.class);
        when(permissions.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        when(discovery.resolveToolId(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME))
                .thenReturn("tool-11111111111111111111111111111111");
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, execution);
        adapter.bindDiscovery(discovery);
        adapter.bindPermissions(permissions, "tool-11111111111111111111111111111111");
        execution.installShowcaseConfirmation(adapter);
        AtomicInteger calls = new AtomicInteger();
        execution.installConfirmationHandler(context -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("projection failed");
            }
            return BeforeToolCallResult.allow();
        });
        ToolCall first = showcaseCall("call-1", "first");
        ToolCall second = showcaseCall("call-2", "second");

        assertThatThrownBy(() -> execution.beforeToolCall(context(first))).isInstanceOf(IllegalStateException.class);
        assertThat(execution.beforeToolCall(context(second))).isEqualTo(BeforeToolCallResult.allow());
        assertThat(execution.preparedShowcaseQuestion(second)).isPresent();
    }

    @Test
    void shouldResumePendingConfirmationOnAcceptedSegment() {
        CapturingOutput initial = new CapturingOutput();
        CapturingOutput continuation = new CapturingOutput();
        RuntimeActiveExecution execution = new RuntimeActiveExecution(initial);
        ExecutionTargetDTO original = new ExecutionTargetDTO("session", "execution", "root", "segment-1");
        ExecutionTargetDTO resumed = new ExecutionTargetDTO("session", "execution", "root", "segment-2");
        execution.bindTarget(original);
        var pending = execution.beginToolConfirmation("call-1");
        assertThat(execution.stageContinuationOutput(resumed, continuation)).isTrue();
        ToolConfirmationDecisionDTO decision = decision("call-1", "segment-1", "segment-2");

        assertThat(execution.resumeToolConfirmation(decision)).isTrue();

        assertThat(pending).isCompletedWithValue(decision);
        assertThat(execution.target()).isEqualTo(resumed);
        assertThat(execution.output()).isSameAs(continuation);
    }

    @Test
    void shouldKeepPendingConfirmationForMismatchedDecision() {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.bindTarget(new ExecutionTargetDTO("session", "execution", "root", "segment-1"));
        var pending = execution.beginToolConfirmation("call-1");

        assertThat(execution.resumeToolConfirmation(decision("call-2", "segment-1", "segment-2")))
                .isFalse();
        assertThat(pending).isNotDone();
        assertThat(execution.target().segmentId()).isEqualTo("segment-1");
    }

    @Test
    void shouldDeliverApprovalClaimedBeforeToolStartsWaiting() throws Exception {
        RuntimeActiveExecution execution = planExecution();
        AtomicInteger applied = new AtomicInteger();
        execution.installModeApplier(applied::incrementAndGet);
        execution.beginPlanReview("exit-1", "plan-1", "# 方案");
        PlanReviewDecisionDTO decision = planDecision(PlanConfirmationResult.APPROVE);

        assertThat(execution.claimAndResumePlanReview(
                        new ExecutionTargetDTO("session", "execution", "root", "segment-1"),
                        "plan-1",
                        () -> Optional.of(decision)))
                .contains(decision);

        assertThat(execution.await("exit-1", "# 方案"))
                .extracting("approved", "feedback")
                .containsExactly(true, null);
        assertThat(execution.mode()).isEqualTo(ClawConstants.Agent.MODE_BUILD);
        assertThat(applied).hasValue(1);
    }

    @Test
    void shouldDeliverRevisionClaimedBeforeToolStartsWaiting() throws Exception {
        RuntimeActiveExecution execution = planExecution();
        execution.beginPlanReview("exit-1", "plan-1", "# 方案");
        PlanReviewDecisionDTO decision = planDecision(PlanConfirmationResult.REVISE);
        decision.setFeedback("补充回滚步骤");

        execution.claimAndResumePlanReview(
                new ExecutionTargetDTO("session", "execution", "root", "segment-1"),
                "plan-1",
                () -> Optional.of(decision));

        assertThat(execution.await("exit-1", "# 方案"))
                .extracting("approved", "feedback")
                .containsExactly(false, "补充回滚步骤");
        assertThat(execution.mode()).isEqualTo(ClawConstants.Agent.MODE_PLAN);
    }

    @Test
    void shouldDeliverQuestionAnswerClaimedBeforeToolStartsWaiting() throws Exception {
        RuntimeActiveExecution execution = planExecution();
        QuestionRequestSnapshotDTO snapshot =
                new QuestionRequestSnapshotDTO(List.of(new QuestionDTO("q", "Question", null, List.of(), false)));
        execution.beginQuestion("ask-1", "question-1", snapshot);
        QuestionAnswerDecisionDTO decision = questionDecision();

        assertThat(execution.claimAndResumeQuestionAnswer(
                        new ExecutionTargetDTO("session", "execution", "root", "segment-1"),
                        "question-1",
                        () -> Optional.of(decision)))
                .contains(decision);

        assertThat(execution.await("ask-1", new com.huawei.hicampus.claw.agent.tool.CancellationToken()))
                .isEqualTo(decision.getAnswers());
        assertThat(execution.mode()).isEqualTo(ClawConstants.Agent.MODE_PLAN);
    }

    @Test
    void stagesContinuationBeforeCompletingQuestionFuture() {
        CapturingOutput continuation = new CapturingOutput();
        RuntimeActiveExecution execution = planExecution();
        QuestionRequestSnapshotDTO snapshot =
                new QuestionRequestSnapshotDTO(List.of(new QuestionDTO("q", "Question", null, List.of(), false)));
        var pending = execution.beginQuestion("ask-1", "question-1", snapshot);
        pending.thenAccept(ignored -> {
            try {
                execution.await("ask-1", new com.huawei.hicampus.claw.agent.tool.CancellationToken());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        ExecutionTargetDTO resumed = new ExecutionTargetDTO("session", "execution", "root", "segment-2");
        assertThat(execution.stageQuestionContinuationOutput(resumed, "question-1", continuation))
                .isTrue();

        execution.claimAndResumeQuestionAnswer(execution.target(), "question-1", () -> Optional.of(questionDecision()));

        assertThat(execution.target()).isEqualTo(resumed);
        assertThat(execution.output()).isSameAs(continuation);
    }

    private static BeforeToolCallContext context(ToolCall call) {
        return new BeforeToolCallContext(null, call, call.arguments(), null);
    }

    private static ToolCall showcaseCall(String id, String label) {
        return new ToolCall(
                id,
                "CallMateTool",
                Map.of(
                        "tool",
                        ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                        "args",
                        Map.of("label", label)));
    }

    private static ToolConfirmationDecisionDTO decision(String toolCallId, String previous, String segment) {
        var decision = new ToolConfirmationDecisionDTO();
        decision.setSessionId("session");
        decision.setExecutionId("execution");
        decision.setConfirmationEventId("confirmation");
        decision.setPreviousSegmentId(previous);
        decision.setSegmentId(segment);
        decision.setToolCallId(toolCallId);
        return decision;
    }

    private static RuntimeActiveExecution planExecution() {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.applyMode(ClawConstants.Agent.MODE_PLAN);
        execution.bindTarget(new ExecutionTargetDTO("session", "execution", "root", "segment-1"));
        return execution;
    }

    private static PlanReviewDecisionDTO planDecision(PlanConfirmationResult result) {
        var decision = new PlanReviewDecisionDTO();
        decision.setSessionId("session");
        decision.setExecutionId("execution");
        decision.setConfirmationEventId("confirmation");
        decision.setTargetEventId("plan-1");
        decision.setPreviousSegmentId("segment-1");
        decision.setSegmentId("segment-2");
        decision.setToolCallId("exit-1");
        decision.setResult(result);
        return decision;
    }

    private static QuestionAnswerDecisionDTO questionDecision() {
        var decision = new QuestionAnswerDecisionDTO();
        decision.setSessionId("session");
        decision.setExecutionId("execution");
        decision.setAnswerEventId("answer-1");
        decision.setTargetEventId("question-1");
        decision.setPreviousSegmentId("segment-1");
        decision.setSegmentId("segment-2");
        decision.setToolCallId("ask-1");
        decision.setAnswers(new QuestionAnswerResultDTO(List.of(new QuestionAnswerDTO("q", List.of(), "answer"))));
        return decision;
    }

    private static final class CapturingOutput implements RuntimeEventOutput {
        @Override
        public void emit(
                java.util.function.Supplier<com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO> event) {}

        @Override
        public void emitBestEffort(
                java.util.function.Supplier<com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO> event) {}

        @Override
        public void complete() {}
    }
}
