/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.agent;

import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.agent.tool.ToolInputSchema;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 只允许按名称调用当前 Agent 直接绑定 Child 的内置 Agent 工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class BoundAgentTool implements AgentTool {

    private static final ObjectNode INPUT_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "agentName": {
                  "type": "string",
                  "description": "Exact name of one directly bound child Agent."
                },
                "task": {
                  "type": "string",
                  "description": "Complete task for the child Agent."
                }
              },
              "required": ["agentName", "task"],
              "additionalProperties": false
            }
            """);

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
        var schema = INPUT_SCHEMA.deepCopy();
        var childNames = parentRuntime.childAgentsByName().keySet();
        if (!childNames.isEmpty()) {
            var names = schema.withObject("properties").withObject("agentName").putArray("enum");
            childNames.stream().sorted().forEach(names::add);
        }
        return schema;
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
