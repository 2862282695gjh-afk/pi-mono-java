/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.BeforeToolCallResult;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanReviewDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.campusclaw.codingagent.runtimeapi.session.PlanConfirmationResult;
import com.campusclaw.common.constant.ClawConstants;

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

    private static BeforeToolCallContext context(ToolCall call) {
        return new BeforeToolCallContext(null, call, call.arguments(), null);
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

    private static final class CapturingOutput implements RuntimeEventOutput {
        @Override
        public void emit(
                java.util.function.Supplier<com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO> event) {}

        @Override
        public void emitBestEffort(
                java.util.function.Supplier<com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO> event) {}

        @Override
        public void complete() {}
    }
}
