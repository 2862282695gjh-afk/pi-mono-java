/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolResolver.ResolvedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 将一个 Agent 直接绑定业务工具的真实定义适配为 Session 级模型工具。
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

    MateToolAdapter(MateToolExecutor executor, ResolvedTool tool) {
        this.executor = executor;
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
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        return executor.executeAgent(name, params, signal);
    }

    private static String normalizeLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
