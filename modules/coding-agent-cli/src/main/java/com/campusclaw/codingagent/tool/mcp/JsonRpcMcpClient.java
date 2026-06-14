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

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final ObjectMapper mapper;
    private final McpTransport transport;
    private final Object initializeLock = new Object();
    private volatile boolean initialized;

    public JsonRpcMcpClient(ObjectMapper mapper, McpTransport transport) {
        this.mapper = mapper;
        this.transport = transport;
    }

    @Override
    public List<McpToolDefinition> listTools() {
        ensureInitialized(null);
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
        ensureInitialized(signal);
        var params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(arguments != null ? arguments : Map.of()));
        JsonNode result = transport.request("tools/call", params, signal);
        return new McpCallResult(readContent(result.path("content")), readDetails(result));
    }

    @Override
    public void close() {
        transport.close();
    }

    private void ensureInitialized(CancellationToken signal) {
        if (initialized) {
            return;
        }
        synchronized (initializeLock) {
            if (initialized) {
                return;
            }
            var params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", mapper.createObjectNode());
            params.set(
                    "clientInfo",
                    mapper.createObjectNode().put("name", "campusclaw").put("version", "1.0.0"));
            JsonNode result = transport.request("initialize", params, signal);
            String serverVersion = result.path("protocolVersion").asText(PROTOCOL_VERSION);
            if (!PROTOCOL_VERSION.equals(serverVersion)) {
                throw new McpException("unsupported MCP protocol version: " + serverVersion);
            }
            transport.notify("notifications/initialized", mapper.createObjectNode());
            initialized = true;
        }
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
