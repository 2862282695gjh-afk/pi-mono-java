/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.Usage;

import org.junit.jupiter.api.Test;

/**
 * 验证计划审核工具参数、完整正文传递及模型可见结果格式。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class ExitPlanModeToolTest {
    @Test
    void passesTheExactPlanAndOmitsNullFeedbackFromApproval() throws Exception {
        String plan = "# 方案\n\n保留完整正文  ";
        var tool = new ExitPlanModeTool((toolCallId, actual) -> {
            assertThat(toolCallId).isEqualTo("exit-1");
            assertThat(actual).isEqualTo(plan);
            return new PlanReviewResultDTO(true, null);
        });

        var result = tool.execute("exit-1", Map.of("plan", plan), new CancellationToken(), ignored -> {});

        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(text).startsWith("{\"approved\":true}\n\n方案已批准").doesNotContain("feedback");
    }

    @Test
    void omitsAbsentRevisionFeedbackAndKeepsRevisionGuidance() throws Exception {
        var tool = new ExitPlanModeTool((toolCallId, plan) -> new PlanReviewResultDTO(false, null));

        var result = tool.execute("exit-2", Map.of("plan", "# 方案"), new CancellationToken(), ignored -> {});

        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(text).startsWith("{\"approved\":false}\n\n方案需要修订");
        assertThat(text.substring(0, text.indexOf("\n\n"))).doesNotContain("feedback");
    }

    @Test
    void semanticArgumentFailureUsesTheStablePublicCode() {
        var tool = new ExitPlanModeTool((toolCallId, plan) -> new PlanReviewResultDTO(true, null));
        ToolCall call = new ToolCall("exit-3", tool.name(), Map.of("plan", "## 二级标题"));

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
