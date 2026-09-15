/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.agent;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

/**
 * 保存一次 Child Agent 调用链的深度、祖先、模型和 thinking 上下文。
 *
 * @param depth 当前父 Session 的 Child 深度
 * @param ancestorAgentIds 包含当前 Agent 的调用路径
 * @param inheritedModel 父 Session 当前模型
 * @param inheritedThinking 父 Session thinking 等级
 * @param mateCredentials 父执行的 Mate 凭据快照
 * @param modeSupplier 实际 Child 调用时读取父模式的函数
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record SubagentExecutionContext(
        int depth,
        Set<String> ancestorAgentIds,
        Model inheritedModel,
        ThinkingLevel inheritedThinking,
        MateCredentials mateCredentials,
        Supplier<String> modeSupplier) {

    public SubagentExecutionContext {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        ancestorAgentIds = Set.copyOf(ancestorAgentIds);
        Objects.requireNonNull(inheritedModel, "inheritedModel");
        inheritedThinking = inheritedThinking == null ? ThinkingLevel.OFF : inheritedThinking;
        mateCredentials = mateCredentials == null ? MateCredentials.empty() : mateCredentials;
        modeSupplier = modeSupplier == null ? () -> "build" : modeSupplier;
    }

    public SubagentExecutionContext(
            int depth,
            Set<String> ancestorAgentIds,
            Model inheritedModel,
            ThinkingLevel inheritedThinking,
            MateCredentials mateCredentials) {
        this(
                depth,
                ancestorAgentIds,
                inheritedModel,
                inheritedThinking,
                mateCredentials,
                () -> ClawConstants.Agent.MODE_BUILD);
    }

    public static SubagentExecutionContext root(
            String agentId, Model model, ThinkingLevel thinkingLevel, MateCredentials credentials) {
        return root(agentId, model, thinkingLevel, credentials, () -> "build");
    }

    public static SubagentExecutionContext root(
            String agentId,
            Model model,
            ThinkingLevel thinkingLevel,
            MateCredentials credentials,
            Supplier<String> modeSupplier) {
        return new SubagentExecutionContext(0, Set.of(agentId), model, thinkingLevel, credentials, modeSupplier);
    }

    public String mode() {
        return modeSupplier.get();
    }
}
