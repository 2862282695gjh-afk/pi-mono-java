/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 在权限判断前固定的单次工具调用，确认放行后执行同一份快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public interface PreparedToolInvocation {

    String toolCallId();

    String toolName();

    Map<String, Object> arguments();

    default String authorizationTargetName() {
        return toolName();
    }

    default String authorizationTargetId() {
        return null;
    }

    AgentToolResult execute(CancellationToken signal, AgentToolUpdateCallback onUpdate) throws Exception;

    static PreparedToolInvocation fixed(AgentTool tool, String toolCallId, Map<String, Object> arguments) {
        Objects.requireNonNull(tool, "tool");
        return new FixedPreparedToolInvocation(
                tool, Objects.requireNonNull(toolCallId, "toolCallId"), snapshotArguments(arguments));
    }

    static Map<String, Object> snapshotArguments(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        arguments.forEach((name, value) -> snapshot.put(name, snapshotValue(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object snapshotValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("Tool argument names must be strings");
                }
                snapshot.put(name, snapshotValue(item));
            });
            return Collections.unmodifiableMap(snapshot);
        }
        if (value instanceof List<?> source) {
            List<Object> snapshot = new ArrayList<>(source.size());
            source.forEach(item -> snapshot.add(snapshotValue(item)));
            return Collections.unmodifiableList(snapshot);
        }
        if (value instanceof JsonNode node) {
            return node.deepCopy();
        }
        return value;
    }
}

record FixedPreparedToolInvocation(AgentTool tool, String toolCallId, Map<String, Object> arguments)
        implements PreparedToolInvocation {

    FixedPreparedToolInvocation {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(arguments, "arguments");
    }

    @Override
    public String toolName() {
        return tool.name();
    }

    @Override
    public Map<String, Object> arguments() {
        return PreparedToolInvocation.snapshotArguments(arguments);
    }

    @Override
    public AgentToolResult execute(CancellationToken signal, AgentToolUpdateCallback onUpdate) throws Exception {
        return tool.execute(toolCallId, PreparedToolInvocation.snapshotArguments(arguments), signal, onUpdate);
    }
}
