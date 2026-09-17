/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按模型给出的名称解析并执行一个 Mate 工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class CallMateTool implements AgentTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(CallMateTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MateToolClient client;

    private final MateCredentials credentials;

    private final MateToolDiscovery discovery;

    private final Map<String, InvocationBinding> invocationBindings = new ConcurrentHashMap<>();

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

    /**
     * 在首次调用事件提交前固定具体 Mate 工具解析结果。
     *
     * @param toolCallId 模型工具调用标识
     * @param toolName 模型使用的工具名称
     * @return 可选的用户展示名称
     * @throws IllegalStateException 同一调用标识尝试绑定不同工具名时抛出
     */
    public String bindInvocation(String toolCallId, String toolName) {
        if (toolCallId == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        InvocationBinding binding = invocationBindings.computeIfAbsent(toolCallId, ignored -> resolve(toolName));
        if (!binding.toolName().equals(toolName)) {
            throw new IllegalStateException("Mate tool call id was rebound to a different name");
        }
        return binding.resolution() == null ? null : binding.resolution().displayName();
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
        InvocationBinding binding = invocationBindings.computeIfAbsent(toolCallId, ignored -> resolve(toolName));
        if (!binding.toolName().equals(toolName)) {
            throw new IllegalStateException("Mate tool call id was rebound to a different name");
        }
        if (binding.failure() != null) {
            throw binding.failure();
        }
        MateToolResolutionDTO resolution = binding.resolution();
        if (resolution == null) {
            throw new MateToolExecutionException(toolName, "unknown Mate tool name");
        }
        ensureNotCancelled(signal);
        MateToolClient.ToolResult result = client.callTool(resolution.toolId(), args, credentials);
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

    private InvocationBinding resolve(String toolName) {
        try {
            return new InvocationBinding(toolName, discovery.resolveTool(toolName), null);
        } catch (RuntimeException failure) {
            LOGGER.atWarn()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "mate.tool.resolve")
                    .addKeyValue("toolName", toolName)
                    .setCause(failure)
                    .log("Mate tool resolution failed");
            return new InvocationBinding(toolName, null, failure);
        }
    }

    private record InvocationBinding(String toolName, MateToolResolutionDTO resolution, RuntimeException failure) {}

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
