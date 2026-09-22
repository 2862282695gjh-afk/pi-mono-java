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

    /**
     * 在权限判断前固定本次调用参数及执行目标。
     *
     * @param toolCallId 工具调用标识
     * @param params 已通过注册 Schema 校验的参数
     * @return 不可变调用快照
     * @throws Exception 准备失败
     */
    default PreparedToolInvocation prepare(String toolCallId, Map<String, Object> params) throws Exception {
        return PreparedToolInvocation.fixed(this, toolCallId, params);
    }

    AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception;
}
