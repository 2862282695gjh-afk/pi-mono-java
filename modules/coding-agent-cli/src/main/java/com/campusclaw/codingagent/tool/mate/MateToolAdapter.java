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
import com.campusclaw.codingagent.tool.mate.MateToolResolver.ResolvedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 将一个 Agent 直接关联的业务工具定义适配为 Session 级模型工具，调用仍需 Host 权限检查。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolAdapter implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MateToolExecutor executor;

    private final String name;

    private final String label;

    private final String description;

    private final JsonNode parameters;

    private final ResolvedTool tool;

    MateToolAdapter(MateToolExecutor executor, ResolvedTool tool) {
        this.executor = executor;
        this.tool = tool;
        this.name = tool.binding().name();
        this.label = normalizeLabel(tool.binding().displayName(), name);
        this.description =
                tool.metadata().description() == null ? "" : tool.metadata().description();
        this.parameters = MAPPER.valueToTree(tool.metadata().inputSchema());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }

    @Override
    public PreparedToolInvocation prepare(String toolCallId, Map<String, Object> params) {
        return executor.prepareAgent(toolCallId, tool, parameters, params);
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        return executor.prepareValidatedAgent(toolCallId, tool, parameters, params)
                .execute(signal, onUpdate);
    }

    private static String normalizeLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
