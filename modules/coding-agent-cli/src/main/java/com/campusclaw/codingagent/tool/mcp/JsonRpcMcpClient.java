/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON-RPC MCP client implementation.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class JsonRpcMcpClient implements McpClient {

    private final ObjectMapper mapper;
    private final McpTransport transport;

    public JsonRpcMcpClient(ObjectMapper mapper, McpTransport transport) {
        this.mapper = mapper;
        this.transport = transport;
    }

    @Override
    public List<McpToolDefinition> listTools() {
        JsonNode result = transport.request("tools/list", mapper.createObjectNode());
        JsonNode tools = result.path("tools");
        var definitions = new ArrayList<McpToolDefinition>();
        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                definitions.add(new McpToolDefinition(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.has("inputSchema")
                                ? tool.get("inputSchema")
                                : mapper.createObjectNode().put("type", "object")));
            }
        }
        return List.copyOf(definitions);
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments, CancellationToken signal) {
        var params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(arguments != null ? arguments : Map.of()));
        JsonNode result = transport.request("tools/call", params);
        return new McpCallResult(readContent(result.path("content")), readDetails(result));
    }

    @Override
    public void close() {
        transport.close();
    }

    private List<McpContent> readContent(JsonNode content) {
        if (!content.isArray()) {
            return List.of();
        }
        var blocks = new ArrayList<McpContent>();
        for (JsonNode item : content) {
            blocks.add(
                    new McpContent(item.path("type").asText(), item.path("text").asText("")));
        }
        return List.copyOf(blocks);
    }

    private Object readDetails(JsonNode result) {
        if (result.has("structuredContent")) {
            return mapper.convertValue(result.get("structuredContent"), Object.class);
        }
        return mapper.convertValue(result, Object.class);
    }
}
