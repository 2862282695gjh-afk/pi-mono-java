/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;

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
