/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin;

import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 保持已配置工具可见但在调用时返回 unavailable 的占位实现。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class UnavailableBuiltInTool implements AgentTool {

    private final BuiltInToolName name;

    private final JsonNode parameters;

    public UnavailableBuiltInTool(BuiltInToolName name, JsonNode parameters) {
        this.name = name;
        this.parameters = parameters.deepCopy();
    }

    @Override
    public String name() {
        return name.externalName();
    }

    @Override
    public String label() {
        return name.externalName();
    }

    @Override
    public String description() {
        return BuiltInToolContracts.description(name);
    }

    @Override
    public ToolExecutionMode executionMode() {
        return name.executionMode();
    }

    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate) {
        throw new IllegalStateException(name.externalName() + " is unavailable in the current execution context");
    }
}
