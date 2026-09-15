/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.ToolResultMessage;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.codingagent.tool.todo.TodoItemDTO;
import com.huawei.hicampus.claw.codingagent.tool.todo.TodoSnapshotDTO;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * 从可信 TodoUpdate 定稿调用及最终成功结果生成任务事件快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeTodoEventMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Optional<TodoSnapshotDTO> map(ToolCall call, ToolResultMessage result) {
        if (call == null
                || !BuiltInToolName.TODO_UPDATE.externalName().equals(call.name())
                || !call.id().equals(result.toolCallId())) {
            return Optional.empty();
        }
        if (result.isError()) {
            return Optional.empty();
        }
        return Optional.of(parseFinalResult(result));
    }

    private static TodoSnapshotDTO parseFinalResult(ToolResultMessage result) {
        List<com.huawei.hicampus.claw.ai.types.ContentBlock> content = result.content();
        if (content.size() != 1 || !(content.getFirst() instanceof TextContent text)) {
            throw new IllegalStateException("TodoUpdate success must contain exactly one text block");
        }
        try {
            JsonNode root = MAPPER.readTree(text.text());
            if (root == null || !root.isObject() || !fieldNames(root).equals(Set.of("todos"))) {
                throw invalid();
            }
            return parseTodos(root.get("todos"));
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("TodoUpdate final result is invalid", error);
        }
    }

    private static TodoSnapshotDTO parseTodos(JsonNode todos) {
        if (todos == null || !todos.isArray() || todos.size() > ClawConstants.RuntimeApi.MAX_TODOS) {
            throw invalid();
        }
        List<TodoItemDTO> result = new ArrayList<>();
        Set<String> contents = new HashSet<>();
        int active = 0;
        for (JsonNode value : todos) {
            TodoItemDTO item = parseItem(value);
            if (!contents.add(item.content())) {
                throw invalid();
            }
            active += "in_progress".equals(item.status()) ? 1 : 0;
            result.add(item);
        }
        if (active > 1) {
            throw invalid();
        }
        return new TodoSnapshotDTO(result);
    }

    private static TodoItemDTO parseItem(JsonNode value) {
        if (value == null || !value.isObject() || !fieldNames(value).equals(Set.of("content", "status"))) {
            throw invalid();
        }
        JsonNode contentNode = value.get("content");
        JsonNode statusNode = value.get("status");
        if (!contentNode.isTextual() || !statusNode.isTextual()) {
            throw invalid();
        }
        String content = contentNode.textValue();
        String status = statusNode.textValue();
        if (content.isBlank()
                || !content.equals(content.strip())
                || content.length() > ClawConstants.RuntimeApi.MAX_TODO_CONTENT_CHARACTERS
                || !Set.of("pending", "in_progress", "completed").contains(status)) {
            throw invalid();
        }
        return new TodoItemDTO(content, status);
    }

    private static Set<String> fieldNames(JsonNode value) {
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("TodoUpdate final result is invalid");
    }
}
