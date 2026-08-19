/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

/**
 * 判定发起调用的主体能否访问目标 Agent。这是
 * {@code mainagent-subagent-design.md} §2.3 中 {@code effectiveChildAgents}
 * 的鉴权项，在每次委派执行前复查；它补充、绝不取代直接绑定规则。
 *
 * <p>真实的租户/用户鉴权在主体传播贯通受管 Agent 入口后接入。本地 CLI
 * 运行保持 {@link #PERMIT_ALL}，即仅以直接绑定作为唯一门禁。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public interface AgentAuthorizationPolicy {

    /** 放行所有主体；真实鉴权接入前的占位实现。 */
    AgentAuthorizationPolicy PERMIT_ALL = (principal, agentId) -> true;

    /**
     * 判定主体能否调用目标 Agent。
     *
     * @param principal 发起调用的用户身份
     * @param agentId   目标 Agent id
     * @return 鉴权裁决
     */
    boolean isAuthorized(AgentPrincipal principal, String agentId);

    /**
     * 发起调用的用户身份。本地 CLI 运行不携带租户上下文时两个字段均为 {@code null}。
     *
     * @param tenantId 发起调用的租户
     * @param userId   发起调用的用户
     */
    record AgentPrincipal(String tenantId, String userId) {}
}
