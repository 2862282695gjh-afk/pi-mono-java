/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.todo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolArgumentsInvalidException;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 校验并返回 build 实施阶段完整任务清单的无状态工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class TodoUpdateTool implements AgentTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    private static final String DESCRIPTION = "Track implementation progress in build mode; do not call this tool "
            + "during plan-mode research or proposal design. Before multi-step implementation, submit the complete "
            + "task list and mark the first active task in_progress. Mark each task completed immediately after its "
            + "work and required verification finish; do not batch independent completions. The same call may "
            + "complete the previous task and start the next one. Send the entire list every time, retaining "
            + "completed tasks that should remain visible; use pending, in_progress, or completed with at most one "
            + "task in_progress. Skip trivial work. Do not mark failed, partial, or unverified work completed. Adjust "
            + "the list when the work changes within the authorized scope; remove cancelled items with an "
            + "explanation instead of marking them completed. Read the result and do not claim a failed update was "
            + "saved. Submit the required full list again when a new user-message execution continues earlier work. "
            + "This tool reports progress only and does not execute tasks, approve proposals, change session mode, "
            + "or replace the final answer.";

    @Override
    public String name() {
        return BuiltInToolName.TODO_UPDATE.externalName();
    }

    @Override
    public String label() {
        return name();
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode parameters() {
        ObjectNode item = MAPPER.createObjectNode().put("type", "object");
        ObjectNode fields = item.putObject("properties");
        fields.putObject("content")
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", ClawConstants.RuntimeApi.MAX_TODO_CONTENT_CHARACTERS);
        var status = fields.putObject("status").put("type", "string").putArray("enum");
        STATUSES.forEach(status::add);
        item.putArray("required").add("content").add("status");
        item.put("additionalProperties", false);
        ObjectNode todos = MAPPER.createObjectNode()
                .put("type", "array")
                .put("maxItems", ClawConstants.RuntimeApi.MAX_TODOS)
                .<ObjectNode>set("items", item);
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.putObject("properties").set("todos", todos);
        schema.putArray("required").add("todos");
        return schema.put("additionalProperties", false);
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate) {
        if (params == null || !params.keySet().equals(Set.of("todos")) || !(params.get("todos") instanceof List<?>)) {
            throw new ToolArgumentsInvalidException("todos must be the only parameter and must be an array");
        }
        TodoSnapshotDTO snapshot = normalize((List<?>) params.get("todos"));
        String json = encode(snapshot);
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(json)), snapshot);
    }

    private static TodoSnapshotDTO normalize(List<?> values) {
        if (values.size() > ClawConstants.RuntimeApi.MAX_TODOS) {
            throw new ToolArgumentsInvalidException("todos must contain at most 100 items");
        }
        List<TodoItemDTO> todos = new ArrayList<>();
        Set<String> contents = new HashSet<>();
        int active = 0;
        for (Object value : values) {
            TodoItemDTO todo = normalizeItem(value);
            if (!contents.add(todo.content())) {
                throw new ToolArgumentsInvalidException("todo content must be unique");
            }
            active += "in_progress".equals(todo.status()) ? 1 : 0;
            todos.add(todo);
        }
        if (active > 1) {
            throw new ToolArgumentsInvalidException("at most one todo may be in_progress");
        }
        return new TodoSnapshotDTO(todos);
    }

    private static TodoItemDTO normalizeItem(Object value) {
        if (!(value instanceof Map<?, ?> item) || !item.keySet().equals(Set.of("content", "status"))) {
            throw new ToolArgumentsInvalidException("each todo must contain only content and status");
        }
        if (!(item.get("content") instanceof String content) || !(item.get("status") instanceof String status)) {
            throw new ToolArgumentsInvalidException("todo content and status must be strings");
        }
        String normalized = content.strip();
        if (normalized.isEmpty() || normalized.length() > ClawConstants.RuntimeApi.MAX_TODO_CONTENT_CHARACTERS) {
            throw new ToolArgumentsInvalidException("todo content length is invalid");
        }
        if (!STATUSES.contains(status)) {
            throw new ToolArgumentsInvalidException("todo status is invalid");
        }
        return new TodoItemDTO(normalized, status);
    }

    private static String encode(TodoSnapshotDTO snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception error) {
            throw new IllegalStateException("failed to encode TodoUpdate result", error);
        }
    }
}
