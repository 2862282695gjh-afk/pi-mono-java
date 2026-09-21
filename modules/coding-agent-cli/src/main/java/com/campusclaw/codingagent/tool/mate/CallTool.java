/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 按可信直接 Skill 绑定的业务名称调用 Mate 工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class CallTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MateToolExecutor executor;

    public CallTool(MateToolExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return "CallTool";
    }

    @Override
    public String label() {
        return "Call Tool";
    }

    @Override
    public String description() {
        return "Call a managed business tool available through a directly bound Skill. Read its definition when needed.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public JsonNode parameters() {
        var properties = MAPPER.createObjectNode();
        properties.set(
                "tool",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Exact business tool name from a directly bound Skill's definition file."));
        properties.set(
                "args",
                MAPPER.createObjectNode()
                        .put("type", "object")
                        .put(
                                "description",
                                "Arguments for the selected business tool; omitted means an empty object."));
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("tool"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        String toolName = (String) params.get("tool");
        Map<String, Object> args =
                params.get("args") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
        return executor.executeSkill(toolName, args, signal);
    }
}
