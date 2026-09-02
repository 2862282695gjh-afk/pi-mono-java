/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.huawei.hicampus.claw.agent.tool.AfterToolCallHandler;
import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallHandler;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;

/**
 * 保存创建公共 Agent Session 所需的不可变参数。
 *
 * @param agentId 待准备的受管 Agent 标识
 * @param entryPoint Session 创建入口
 * @param modelResolver 基于同一运行时快照解析当前模型的函数
 * @param thinkingLevel thinking 等级
 * @param mateCredentials 本次执行的 Mate 凭据快照，不持久化
 * @param cronToolFactory Runtime Cron 工具工厂
 * @param agentToolFactory Runtime 或 Cron 的 Child 工具工厂
 * @param runtimeValidator 入口专属运行时校验器
 * @param beforeHooks 创建时固定的前置 hook 链
 * @param afterHooks 创建时固定的后置 hook 链
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record ManagedAgentSessionRequest(
        String agentId,
        ToolEntryPoint entryPoint,
        Function<PreparedAgentRuntime, Model> modelResolver,
        ThinkingLevel thinkingLevel,
        MateCredentials mateCredentials,
        BiFunction<PreparedAgentRuntime, Model, AgentTool> cronToolFactory,
        BiFunction<PreparedAgentRuntime, Model, AgentTool> agentToolFactory,
        Consumer<PreparedAgentRuntime> runtimeValidator,
        List<BeforeToolCallHandler> beforeHooks,
        List<AfterToolCallHandler> afterHooks) {

    public ManagedAgentSessionRequest {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Objects.requireNonNull(entryPoint, "entryPoint");
        Objects.requireNonNull(modelResolver, "modelResolver");
        thinkingLevel = thinkingLevel == null ? ThinkingLevel.OFF : thinkingLevel;
        mateCredentials = mateCredentials == null ? MateCredentials.empty() : mateCredentials;
        runtimeValidator = runtimeValidator == null ? ignored -> {} : runtimeValidator;
        beforeHooks = beforeHooks == null ? List.of() : List.copyOf(beforeHooks);
        afterHooks = afterHooks == null ? List.of() : List.copyOf(afterHooks);
    }

    public static ManagedAgentSessionRequest create(
            String agentId, ToolEntryPoint entryPoint, Model model, ThinkingLevel thinkingLevel) {
        Objects.requireNonNull(model, "model");
        return new ManagedAgentSessionRequest(
                agentId,
                entryPoint,
                ignored -> model,
                thinkingLevel,
                MateCredentials.empty(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }
}
