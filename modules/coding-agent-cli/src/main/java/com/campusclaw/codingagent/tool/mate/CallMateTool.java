/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 按模型给出的名称解析并执行一个 Mate 工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class CallMateTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MateToolClient client;

    private final MateCredentials credentials;

    private final MateToolDiscovery discovery;

    public CallMateTool(MateToolClient client, MateCredentials credentials, MateToolDiscovery discovery) {
        this.client = client;
        this.credentials = credentials;
        this.discovery = discovery;
    }

    @Override
    public String name() {
        return "CallMateTool";
    }

    @Override
    public String label() {
        return "Call Mate Tool";
    }

    @Override
    public String description() {
        return "Call a Mate tool by name.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public JsonNode parameters() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set(
                "tool",
                MAPPER.createObjectNode().put("type", "string").put("description", "Exact Mate tool name to call."));
        properties.set(
                "args",
                MAPPER.createObjectNode()
                        .put("type", "object")
                        .put("description", "Arguments passed unchanged to the selected Mate tool."));
        ObjectNode schema = MAPPER.createObjectNode();
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
        ensureNotCancelled(signal);
        String toolName = (String) params.get("tool");
        Map<String, Object> args =
                params.get("args") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
        if (!credentials.isComplete()) {
            throw new MateToolExecutionException(toolName, "Mate execution credentials are unavailable");
        }
        String toolId = discovery.resolveToolId(toolName);
        if (toolId == null) {
            throw new MateToolExecutionException(toolName, "unknown Mate tool name");
        }
        ensureNotCancelled(signal);
        MateToolClient.ToolResult result = client.callTool(toolId, args, credentials);
        if (result.isError()) {
            throw new MateToolExecutionException(toolName, result.content());
        }
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(result.content())), result.metadata());
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }

    /**
     * 将发现或执行错误交给工具 Pipeline 投影为 {@code isError=true}。
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/24]
     * @since [br_eCampusCore 26.0.0]
     */
    public static class MateToolExecutionException extends RuntimeException {

        public MateToolExecutionException(String tool, String detail) {
            super("Mate tool '" + tool + "' failed: " + detail);
        }
    }
}
