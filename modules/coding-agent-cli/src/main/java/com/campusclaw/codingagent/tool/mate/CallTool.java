/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.PreparedToolInvocation;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.agent.tool.ToolInputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 按当前 Agent 直接绑定 Skill 的工具关联范围调用 Mate 工具，调用仍需 Host 权限检查。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class CallTool implements AgentTool {

    private static final ObjectNode INPUT_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "tool": {
                  "type": "string",
                  "description": "Exact unique business tool name available through a directly bound Skill."
                },
                "args": {
                  "type": "object",
                  "description": "Arguments for the selected business tool; omitted means an empty object."
                }
              },
              "required": ["tool"],
              "additionalProperties": false
            }
            """);

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
        return "Call a managed business tool available through a directly bound Skill."
                + " Use SearchTools when its definition is needed."
                + " If local validation returns a trusted definition, create a new corrected call from it;"
                + " do not guess fields or repeat unchanged arguments.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public JsonNode parameters() {
        return INPUT_SCHEMA.deepCopy();
    }

    @SuppressWarnings("unchecked")
    @Override
    public PreparedToolInvocation prepare(String toolCallId, Map<String, Object> params) {
        String toolName = (String) params.get("tool");
        Map<String, Object> args =
                params.get("args") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
        return executor.prepareSkill(toolCallId, toolName, args, params);
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        return prepare(toolCallId, params).execute(signal, onUpdate);
    }
}
