/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.Usage;

import org.junit.jupiter.api.Test;

/**
 * 验证计划审核工具空参数、取消传递及模型可见结果格式。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class ExitPlanModeToolTest {
    @Test
    void passesTheCancellationTokenAndOmitsNullFeedbackFromApproval() throws Exception {
        CancellationToken signal = new CancellationToken();
        var tool = new ExitPlanModeTool((toolCallId, actual) -> {
            assertThat(toolCallId).isEqualTo("exit-1");
            assertThat(actual).isSameAs(signal);
            return new PlanReviewResultDTO(true, null);
        });

        var result = tool.execute("exit-1", Map.of(), signal, ignored -> {});

        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(text).startsWith("{\"approved\":true}\n\n方案已批准").doesNotContain("feedback");
    }

    @Test
    void omitsAbsentRevisionFeedbackAndKeepsRevisionGuidance() throws Exception {
        var tool = new ExitPlanModeTool((toolCallId, signal) -> new PlanReviewResultDTO(false, null));

        var result = tool.execute("exit-2", Map.of(), new CancellationToken(), ignored -> {});

        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(text).startsWith("{\"approved\":false}\n\n当前仍为 plan");
        assertThat(text.substring(0, text.indexOf("\n\n"))).doesNotContain("feedback");
    }

    @Test
    void descriptionNamesTheMainAgentAndKeepsApprovalBoundaries() {
        var tool = new ExitPlanModeTool((toolCallId, signal) -> new PlanReviewResultDTO(true, null));

        assertThat(tool.description())
                .contains("Main Agent", "<proposed_plan>", "empty arguments {}", "approved=true", "approved=false")
                .doesNotContain("root agent", "TodoUpdate");
        assertThat(tool.parameters().path("properties").isEmpty()).isTrue();
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void semanticArgumentFailureUsesTheStablePublicCode() {
        var tool = new ExitPlanModeTool((toolCallId, signal) -> new PlanReviewResultDTO(true, null));
        ToolCall call = new ToolCall("exit-3", tool.name(), Map.of("plan", "# 重复方案"));

        var result = new ToolExecutionPipeline()
                .execute(tool, call, call.arguments(), context(call), new CancellationToken(), ignored -> {});

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("TOOL_ARGUMENTS_INVALID");
        assertThat(result.details()).isEqualTo(Map.of("errorCode", "TOOL_ARGUMENTS_INVALID"));
    }

    private static AgentContext context(ToolCall call) {
        AssistantMessage message = new AssistantMessage(
                java.util.List.of(call),
                "api",
                "provider",
                "model",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                1L);
        return new AgentContext(message, java.util.List.of());
    }
}
