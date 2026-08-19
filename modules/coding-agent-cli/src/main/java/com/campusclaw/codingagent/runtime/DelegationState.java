/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.List;
import java.util.Objects;

import com.campusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;

/**
 * 会话级委派状态：一个会话暴露 {@code invoke_agent} 并经
 * {@link LocalAgentDispatcher} 执行一条委派边所需的全部信息。
 *
 * <p>入口会话的 {@code selfContext == null}（按定义深度为 0）；被委派的
 * 子会话携带创建它的 {@link DelegationContext}，深度、祖先链与继续委派
 * 上限均由此推导。
 *
 * @param dispatcher 整条链共享的执行调度器
 * @param conversationId 整条链所服务的会话
 * @param principal 发起调用的用户身份，本地运行可为 null
 * @param selfContext 创建本会话的上下文，入口会话为 null
 * @param wiring 供子会话组装使用的入口会话协作者
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record DelegationState(
        LocalAgentDispatcher dispatcher,
        String conversationId,
        AgentPrincipal principal,
        DelegationContext selfContext,
        DelegationWiring wiring) {

    public DelegationState {
        Objects.requireNonNull(dispatcher, "dispatcher");
        conversationId = conversationId == null || conversationId.isBlank() ? "local" : conversationId;
        Objects.requireNonNull(wiring, "wiring");
    }

    /**
     * 构造入口会话的状态（深度 0，无父边）。
     *
     * @param dispatcher 执行调度器
     * @param conversationId 会话标识，CLI 运行可为 null
     * @param principal 发起调用的用户，CLI 运行可为 null
     * @param wiring 入口会话协作者
     * @return 入口委派状态
     */
    public static DelegationState entry(
            LocalAgentDispatcher dispatcher, String conversationId, AgentPrincipal principal, DelegationWiring wiring) {
        return new DelegationState(dispatcher, conversationId, principal, null, wiring);
    }

    /**
     * 由给定上下文推导其创建的子会话状态。
     *
     * @param parent 父状态，其调度器、身份与协作者原样传递
     * @param childContext 描述该子边的上下文
     * @return 子会话委派状态
     */
    public static DelegationState childOf(DelegationState parent, DelegationContext childContext) {
        return new DelegationState(
                parent.dispatcher(), parent.conversationId(), parent.principal(), childContext, parent.wiring());
    }

    /**
     * 本会话自身 Agent 的深度：入口 Agent 为 0，否则取创建上下文记录的深度。
     *
     * @return 本会话 Agent 的委派深度
     */
    public int depth() {
        return selfContext == null ? 0 : selfContext.delegationDepth();
    }

    /**
     * 判定本会话的 Agent 能否委派：入口 Agent 始终可以，
     * 被委派的 Agent 仅在硬性深度上限之内可以。
     *
     * @return 再委派一次仍在深度上限内时为 true
     */
    public boolean canDelegate() {
        return selfContext == null || selfContext.canDelegateFurther();
    }

    /**
     * 链上已激活的 Agent id 列表（含本会话自身的 Agent）。
     *
     * @param selfAgentId 本会话的 Agent id
     * @return 不可变链，入口在前、本 Agent 在末尾
     */
    public List<String> invocationChain(String selfAgentId) {
        if (selfContext == null) {
            return List.of(selfAgentId);
        }
        var chain = new java.util.ArrayList<>(selfContext.ancestryAgentIds());
        chain.add(selfContext.targetAgentId());
        return List.copyOf(chain);
    }
}
