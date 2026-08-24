/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent;

import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 只允许按名称调用当前 Agent 直接绑定 Child 的内置 Agent 工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class BoundAgentTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PreparedAgentRuntime parentRuntime;

    private final SubagentExecutionContext executionContext;

    private final SubagentExecutionService executionService;

    public BoundAgentTool(
            PreparedAgentRuntime parentRuntime,
            SubagentExecutionContext executionContext,
            SubagentExecutionService executionService) {
        this.parentRuntime = parentRuntime;
        this.executionContext = executionContext;
        this.executionService = executionService;
    }

    @Override
    public String name() {
        return BuiltInToolName.AGENT.externalName();
    }

    @Override
    public String label() {
        return name();
    }

    @Override
    public String description() {
        return "Run one directly bound child Agent and return its final answer.";
    }

    @Override
    public JsonNode parameters() {
        var properties = MAPPER.createObjectNode();
        var agentName = properties
                .putObject("agentName")
                .put("type", "string")
                .put("description", "Exact name of one directly bound child Agent.");
        var childNames = parentRuntime.childAgentsByName().keySet();
        if (!childNames.isEmpty()) {
            var names = agentName.putArray("enum");
            childNames.stream().sorted().forEach(names::add);
        }
        properties.putObject("task").put("type", "string").put("description", "Complete task for the child Agent.");
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties", properties)
                .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                        "required", MAPPER.createArrayNode().add("agentName").add("task"))
                .put("additionalProperties", false);
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate) {
        if (parentRuntime.childAgentsByName().isEmpty()) {
            throw new IllegalStateException("Agent is unavailable in the current execution context");
        }
        String agentName = requireText(params, "agentName");
        String task = requireText(params, "task");
        return executionService.execute(parentRuntime, executionContext, agentName, task, signal, onUpdate);
    }

    private static String requireText(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return text;
    }
}
