/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.tool;

import java.util.Map;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ToolCall;

/**
 * 传递给工具执行前 hook 的原始调用身份和已准备调用快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record BeforeToolCallContext(
        AssistantMessage assistantMessage,
        ToolCall toolCall,
        Map<String, Object> args,
        AgentContext context,
        PreparedToolInvocation preparedInvocation) {

    public BeforeToolCallContext(
            AssistantMessage assistantMessage, ToolCall toolCall, Map<String, Object> args, AgentContext context) {
        this(assistantMessage, toolCall, args, context, null);
    }
}
