/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.Map;
import java.util.function.Function;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Structured control tool used by the model to activate a managed Skill before its
 * business tools become visible.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class ActivateSkillTool implements AgentTool {

    public static final String NAME = "activate_skill";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, AgentToolResult> activator;

    public ActivateSkillTool(Function<String, AgentToolResult> activator) {
        this.activator = activator;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return "Activate Skill";
    }

    @Override
    public String description() {
        return "Activate one available Skill by its exact name. Wait for this tool to finish; "
                + "the Skill instructions and tools are available on the next model turn.";
    }

    @Override
    public JsonNode parameters() {
        var properties = MAPPER.createObjectNode();
        properties.set(
                "skillName",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Exact name from the available Skills list"));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<ObjectNode>set("properties", properties)
                .set("required", MAPPER.createArrayNode().add("skillName"));
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate) {
        Object value = params.get("skillName");
        if (!(value instanceof String skillName) || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required");
        }
        return activator.apply(skillName);
    }
}
