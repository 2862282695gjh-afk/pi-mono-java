/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.tool;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent Runtime 使用的可执行工具契约。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public interface AgentTool {

    String name();

    String label();

    String description();

    JsonNode parameters();

    /**
     * 返回该工具在同一模型调用批次中的执行模式。
     *
     * @return 默认串行执行
     */
    default ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception;
}
