/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AfterToolCallResult;
import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.codingagent.tool.todo.TodoUpdateTool;

import org.junit.jupiter.api.Test;

/**
 * TodoUpdate 最终 after hook 结果的可信快照映射测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeTodoEventMapperTest {
    private final RuntimeTodoEventMapper mapper = new RuntimeTodoEventMapper();

    private final TodoUpdateTool tool = new TodoUpdateTool();

    @Test
    void shouldMapSuccessfulContentChangedByAfterHook() {
        ToolCall call = call();
        ToolExecutionPipeline pipeline = pipeline(new AfterToolCallResult(
                List.of(new TextContent("{\"todos\":[{\"content\":\"最终任务\",\"status\":\"completed\"}]}")), null, null));

        ToolResultMessage result = execute(pipeline, call);

        assertThat(mapper.map(call, result).orElseThrow().todos())
                .singleElement()
                .satisfies(todo -> {
                    assertThat(todo.content()).isEqualTo("最终任务");
                    assertThat(todo.status()).isEqualTo("completed");
                });
    }

    @Test
    void shouldIgnoreResultChangedToFailureByAfterHook() {
        ToolCall call = call();
        ToolExecutionPipeline pipeline = pipeline(new AfterToolCallResult(null, null, true));

        assertThat(mapper.map(call, execute(pipeline, call))).isEmpty();
    }

    @Test
    void shouldRejectInvalidSuccessfulContentChangedByAfterHook() {
        ToolCall call = call();
        ToolExecutionPipeline pipeline = pipeline(new AfterToolCallResult(
                List.of(new TextContent("{\"todos\":[{\"content\":\" x \",\"status\":\"pending\"}]}")), null, false));

        assertThatThrownBy(() -> mapper.map(call, execute(pipeline, call)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TodoUpdate final result is invalid");
    }

    @Test
    void shouldRejectTrailingJsonValues() {
        ToolCall call = call();
        for (String text : List.of("{\"todos\":[]} trailing", "{\"todos\":[]} {\"other\":1}")) {
            ToolResultMessage result =
                    new ToolResultMessage(call.id(), call.name(), List.of(new TextContent(text)), null, false, 1L);
            assertThatThrownBy(() -> mapper.map(call, result))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("TodoUpdate final result is invalid");
        }
    }

    private ToolExecutionPipeline pipeline(AfterToolCallResult result) {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        pipeline.setAfterToolCall(context -> result);
        return pipeline;
    }

    private ToolResultMessage execute(ToolExecutionPipeline pipeline, ToolCall call) {
        return pipeline.execute(tool, call, call.arguments(), new AgentContext(), new CancellationToken(), event -> {});
    }

    private ToolCall call() {
        Map<String, Object> todo = Map.of("content", "原任务", "status", "in_progress");
        Map<String, Object> arguments = Map.of("todos", List.of(todo));
        return new ToolCall("todo-call", tool.name(), arguments);
    }
}
