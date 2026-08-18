/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.campusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentSummary;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.Verdict;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行一条父到子的委派边，统一链上每一跳：复查目标、推导可信的
 * {@link DelegationContext}、准备子运行时，并经 {@link TransientAgentRunner}
 * 运行子 Agent。
 *
 * <p>校验永远先于执行，上下文构造器再次强化结构不变量（深度上限、祖先链、
 * 自绑定）作为纵深防御。每一跳都记录 parent、target、ancestry、depth 与
 * invocationId 日志，保证审计事件 PR 落地之前链路可追溯。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class LocalAgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentDispatcher.class);

    private final AgentBindingResolver resolver;
    private final AgentRuntimeManager runtimeManager;
    private final TransientAgentRunner runner;

    public LocalAgentDispatcher(
            AgentBindingResolver resolver, AgentRuntimeManager runtimeManager, TransientAgentRunner runner) {
        this.resolver = Objects.requireNonNull(resolver);
        this.runtimeManager = Objects.requireNonNull(runtimeManager);
        this.runner = Objects.requireNonNull(runner);
    }

    /**
     * 返回一个会话的 Agent 可委派的子候选。
     *
     * @param state 发起询问的会话的委派状态
     * @param parent 发起委派的父运行时
     * @return 通过 resolver 校验的子摘要；委派关闭时为空列表
     */
    public List<ChildAgentSummary> resolveCandidates(DelegationState state, PreparedAgentRuntime parent) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(parent);
        if (!state.canDelegate()) {
            return List.of();
        }
        AgentPrincipal principal = state.principal() != null ? state.principal() : new AgentPrincipal(null, null);
        return resolver.resolve(parent, principal, state.invocationChain(parent.agentId()));
    }

    /**
     * 执行发起询问的会话的 Agent 发出的一条委派边。
     *
     * @param state 发起询问的会话的委派状态
     * @param parent 发起委派的父运行时
     * @param targetAgentId 请求委派的目标子 Agent id
     * @param task 交给子 Agent 的自包含任务指令
     * @param fallbackModel 子 Agent 未绑定缺省模型时使用的模型
     * @return 子 Agent 的最终答复文本
     * @throws AgentRuntimeException 目标被拒绝或运行失败时抛出
     */
    public String dispatch(
            DelegationState state,
            PreparedAgentRuntime parent,
            String targetAgentId,
            String task,
            String fallbackModel) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(parent);
        if (targetAgentId == null || targetAgentId.isBlank()) {
            throw new AgentRuntimeException("Agent delegation requires an agentId");
        }
        if (task == null || task.isBlank()) {
            throw new AgentRuntimeException("Agent delegation requires a task");
        }
        AgentPrincipal principal = state.principal() != null ? state.principal() : new AgentPrincipal(null, null);
        Verdict verdict = resolver.validate(
                parent, principal, state.invocationChain(parent.agentId()), state.depth(), targetAgentId);
        if (verdict instanceof AgentBindingResolver.Rejected rejected) {
            throw new AgentRuntimeException(
                    "Agent delegation rejected: " + rejected.reason() + " (" + rejected.detail() + ")");
        }
        DelegationContext context = contextFor(state, parent.agentId(), targetAgentId);
        log.info(
                "agent delegation hop: parent={} target={} ancestry={} depth={} invocationId={} conversationId={}",
                parent.agentId(),
                targetAgentId,
                context.ancestryAgentIds(),
                context.delegationDepth(),
                context.invocationId(),
                state.conversationId());
        PreparedAgentRuntime childRuntime = runtimeManager.prepare(targetAgentId);
        DelegationState childState = DelegationState.childOf(state, context);
        return runner.run(childRuntime, childState, task, fallbackModel);
    }

    /**
     * 返回整条链所有会话共享的运行时管理器。
     *
     * @return 唯一的运行时管理器实例
     */
    public AgentRuntimeManager runtimeManager() {
        return runtimeManager;
    }

    private static DelegationContext contextFor(DelegationState state, String parentAgentId, String targetAgentId) {
        String invocationId = UUID.randomUUID().toString();
        if (state.selfContext() == null) {
            return DelegationContext.forEntry(parentAgentId, targetAgentId, state.conversationId(), invocationId);
        }
        return state.selfContext().delegateTo(targetAgentId, invocationId);
    }
}
