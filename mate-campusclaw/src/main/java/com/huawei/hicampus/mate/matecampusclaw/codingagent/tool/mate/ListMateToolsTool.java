/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 实时列出当前 Agent 或一个直接绑定 Skill 的 Mate 工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class ListMateToolsTool implements AgentTool {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final MateToolDiscovery discovery;

    public ListMateToolsTool(MateToolDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public String name() {
        return "ListMateTools";
    }

    @Override
    public String label() {
        return "List Mate Tools";
    }

    @Override
    public String description() {
        return "List live Mate tools for the current Agent or one directly bound Skill.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    @Override
    public JsonNode parameters() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set(
                "skillName",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Exact name of one Skill directly bound to the current Agent."));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        ensureNotCancelled(signal);
        String skillName = params.get("skillName") instanceof String value ? value : null;
        List<MateToolMeta> tools = skillName == null ? discovery.listAgentTools() : discovery.listSkillTools(skillName);
        ensureNotCancelled(signal);
        return textResult(stableJson(skillName, tools));
    }

    private static String stableJson(String skillName, List<MateToolMeta> tools) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode scope = root.putObject("scope");
        scope.put("type", skillName == null ? "agent" : "skill");
        if (skillName != null) {
            scope.put("name", skillName);
        }
        ArrayNode items = root.putArray("tools");
        for (MateToolMeta tool : tools) {
            validateMetadata(tool);
            ObjectNode item = items.addObject();
            item.put("name", tool.toolName());
            item.put("description", tool.description() == null ? "" : tool.description());
            item.set("inputSchema", MAPPER.valueToTree(tool.inputSchema() == null ? Map.of() : tool.inputSchema()));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void validateMetadata(MateToolMeta tool) {
        if (tool.toolName() == null || tool.toolName().isBlank() || tool.toolId() == null) {
            throw new IllegalStateException("Mate tool metadata is incomplete");
        }
    }

    private static AgentToolResult textResult(String text) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), null);
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }
}
