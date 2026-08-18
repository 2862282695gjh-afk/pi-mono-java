/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.skill;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.catalog.ControlTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * Stateless control tool used by the model to activate a managed Skill before
 * its business tools become visible. The tool itself only acknowledges the
 * request; the session-level after-tool-call handler performs the actual
 * activation and replaces the acknowledgement with the Skill instructions.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ActivateSkillTool implements ControlTool {

    public static final String NAME = "activate_skill";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return new AgentToolResult(List.of(new TextContent("Skill activation requested: " + skillName)), null);
    }
}
