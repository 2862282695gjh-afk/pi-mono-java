/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanTextSnapshotDTO;
import com.campusclaw.codingagent.tool.plan.PlanReviewException;

import org.junit.jupiter.api.Test;

/**
 * 计划候选固定来源、唯一调用与终止原因绑定测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
class PlanProposalBinderTest {
    private final PlanProposalBinder binder = new PlanProposalBinder();

    private final ExecutionTargetDTO target = new ExecutionTargetDTO("session", "execution", "root", "segment");

    @Test
    void shouldBindTheExactAssistantAndEmptyExitCall() {
        ToolCall call = exitCall(Map.of());
        AssistantMessage assistant = assistant(List.of(new TextContent("plan"), call), StopReason.TOOL_USE);

        var result = binder.bind(
                target,
                "root",
                "assistant-entry",
                "plan-event",
                new PlanTextSnapshotDTO("", "# 方案", true),
                assistant,
                context(assistant, call));

        assertThat(result.target()).isEqualTo(target);
        assertThat(result.assistantEntryId()).isEqualTo("assistant-entry");
        assertThat(result.planEventId()).isEqualTo("plan-event");
        assertThat(result.toolCallId()).isEqualTo("exit");
        assertThat(result.content()).isEqualTo("# 方案");
    }

    @Test
    void shouldRejectAnotherAssistantInstanceAndNonToolUseStop() {
        ToolCall call = exitCall(Map.of());
        AssistantMessage assistant = assistant(List.of(call), StopReason.TOOL_USE);
        AssistantMessage copy = assistant(List.of(call), StopReason.TOOL_USE);

        assertUnavailable(assistant, context(copy, call), new PlanTextSnapshotDTO("", "# 方案", true));
        AssistantMessage stopped = assistant(List.of(call), StopReason.STOP);
        assertUnavailable(stopped, context(stopped, call), new PlanTextSnapshotDTO("", "# 方案", true));
    }

    @Test
    void shouldRejectInvalidSnapshotArgumentsAndMixedCalls() {
        ToolCall withPlan = exitCall(Map.of("plan", "# 重复"));
        AssistantMessage argumentMessage = assistant(List.of(withPlan), StopReason.TOOL_USE);
        assertUnavailable(
                argumentMessage, context(argumentMessage, withPlan), new PlanTextSnapshotDTO("", "# 方案", true));

        ToolCall exit = exitCall(Map.of());
        ToolCall other = new ToolCall("other", "Read", Map.of());
        AssistantMessage mixed = assistant(List.of(exit, other), StopReason.TOOL_USE);
        assertUnavailable(mixed, context(mixed, exit), new PlanTextSnapshotDTO("", "# 方案", true));
        assertUnavailable(mixed, context(mixed, exit), new PlanTextSnapshotDTO("", "# 方案", false));
    }

    @Test
    void shouldRejectNonBlankTextAfterExitCall() {
        ToolCall call = exitCall(Map.of());
        AssistantMessage assistant =
                assistant(List.of(new TextContent("plan"), call, new TextContent("extra")), StopReason.TOOL_USE);

        assertUnavailable(assistant, context(assistant, call), new PlanTextSnapshotDTO("", "# 方案", true));
    }

    private void assertUnavailable(
            AssistantMessage assistant, BeforeToolCallContext context, PlanTextSnapshotDTO text) {
        assertThatThrownBy(() -> binder.bind(target, "root", "assistant-entry", "plan-event", text, assistant, context))
                .isInstanceOf(PlanReviewException.class)
                .hasMessage("PLAN_REVIEW_UNAVAILABLE");
    }

    private static ToolCall exitCall(Map<String, Object> arguments) {
        return new ToolCall("exit", "ExitPlanMode", arguments);
    }

    private static BeforeToolCallContext context(AssistantMessage assistant, ToolCall call) {
        return new BeforeToolCallContext(assistant, call, call.arguments(), null);
    }

    private static AssistantMessage assistant(
            List<com.campusclaw.ai.types.ContentBlock> content, StopReason stopReason) {
        return new AssistantMessage(content, "api", "provider", "model", null, Usage.empty(), stopReason, null, 1L);
    }
}
