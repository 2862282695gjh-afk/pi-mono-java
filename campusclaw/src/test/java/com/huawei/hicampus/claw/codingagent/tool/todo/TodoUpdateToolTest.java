/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.Usage;

import org.junit.jupiter.api.Test;

/**
 * 验证 TodoUpdate 完整快照、规范化及稳定失败契约。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class TodoUpdateToolTest {
    private final TodoUpdateTool tool = new TodoUpdateTool();

    @Test
    void returnsOneExactTextSnapshotAndRunsSequentially() {
        var result = tool.execute(
                "todo-1",
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "  实施  ", "status", "in_progress"),
                                Map.of("content", "验证", "status", "pending"))),
                new CancellationToken(),
                ignored -> {});

        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(result.content())
                .containsExactly(new TextContent("{\"todos\":[{\"content\":\"实施\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"验证\",\"status\":\"pending\"}]}"));
    }

    @Test
    void acceptsEmptyClearAndPreservesNonBreakingSpace() {
        var empty = tool.execute("todo-clear", Map.of("todos", List.of()), new CancellationToken(), ignored -> {});
        var nbsp = tool.execute(
                "todo-nbsp",
                Map.of("todos", List.of(Map.of("content", "\u00a0", "status", "completed"))),
                new CancellationToken(),
                ignored -> {});

        assertThat(((TextContent) empty.content().getFirst()).text()).isEqualTo("{\"todos\":[]}");
        assertThat(((TextContent) nbsp.content().getFirst()).text()).contains("\u00a0");
    }

    @Test
    void semanticFailuresUseTheStablePublicCode() {
        ToolCall call = new ToolCall(
                "todo-invalid",
                tool.name(),
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "重复", "status", "pending"),
                                Map.of("content", " 重复 ", "status", "completed"))));

        var result = new ToolExecutionPipeline()
                .execute(tool, call, call.arguments(), context(call), new CancellationToken(), ignored -> {});

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("TOOL_ARGUMENTS_INVALID");
        assertThat(result.details()).isEqualTo(Map.of("errorCode", "TOOL_ARGUMENTS_INVALID"));
    }

    @Test
    void rejectsMoreThanOneActiveItemAfterSchemaValidation() {
        ToolCall call = new ToolCall(
                "todo-active",
                tool.name(),
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "一", "status", "in_progress"),
                                Map.of("content", "二", "status", "in_progress"))));

        var result = new ToolExecutionPipeline()
                .execute(tool, call, call.arguments(), context(call), new CancellationToken(), ignored -> {});

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("TOOL_ARGUMENTS_INVALID");
    }

    private static AgentContext context(ToolCall call) {
        AssistantMessage message = new AssistantMessage(
                List.of(call), "api", "provider", "model", null, Usage.empty(), StopReason.TOOL_USE, null, 1L);
        return new AgentContext(message, List.of());
    }
}
