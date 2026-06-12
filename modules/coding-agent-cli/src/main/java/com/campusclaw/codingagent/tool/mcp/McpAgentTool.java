/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@link AgentTool} adapter for an MCP tool.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class McpAgentTool implements AgentTool {

    private final String exposedName;
    private final String label;
    private final McpToolDefinition definition;
    private final McpClient client;

    public McpAgentTool(String exposedName, String label, McpToolDefinition definition, McpClient client) {
        this.exposedName = exposedName;
        this.label = label;
        this.definition = definition;
        this.client = client;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public JsonNode parameters() {
        return definition.inputSchema();
    }

    @Override
    public AgentToolResult execute(
            String toolCallId,
            Map<String, Object> params,
            CancellationToken signal,
            AgentToolUpdateCallback onUpdate) {
        var result = client.callTool(definition.name(), params, signal);
        return new AgentToolResult(McpContentMapper.toContentBlocks(result.content()), result.details());
    }
}
