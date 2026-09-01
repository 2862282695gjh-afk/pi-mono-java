/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.state;

import java.util.List;
import java.util.Set;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;

/**
 * Immutable snapshot of the current agent state.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record AgentStateSnapshot(
        String systemPrompt,
        Model model,
        ThinkingLevel thinkingLevel,
        List<AgentTool> tools,
        List<Message> messages,
        boolean streaming,
        Message streamMessage,
        Set<String> pendingToolCalls,
        String error) {

    public AgentStateSnapshot {
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
        pendingToolCalls = Set.copyOf(pendingToolCalls);
    }
}
