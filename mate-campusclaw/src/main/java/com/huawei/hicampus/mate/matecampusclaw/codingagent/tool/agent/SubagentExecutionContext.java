/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent;

import java.util.Objects;
import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;

/**
 * 保存一次 Child Agent 调用链的深度、祖先、模型和 thinking 上下文。
 *
 * @param depth 当前父 Session 的 Child 深度
 * @param ancestorAgentIds 包含当前 Agent 的调用路径
 * @param inheritedModel 父 Session 当前模型
 * @param inheritedThinking 父 Session thinking 等级
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record SubagentExecutionContext(
        int depth, Set<String> ancestorAgentIds, Model inheritedModel, ThinkingLevel inheritedThinking) {

    public SubagentExecutionContext {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        ancestorAgentIds = Set.copyOf(ancestorAgentIds);
        Objects.requireNonNull(inheritedModel, "inheritedModel");
        inheritedThinking = inheritedThinking == null ? ThinkingLevel.OFF : inheritedThinking;
    }

    public static SubagentExecutionContext root(String agentId, Model model, ThinkingLevel thinkingLevel) {
        return new SubagentExecutionContext(0, Set.of(agentId), model, thinkingLevel);
    }
}
