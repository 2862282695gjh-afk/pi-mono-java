/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

/**
 * Decides whether an invoking principal may reach a target Agent. This is the
 * authorization term of {@code effectiveChildAgents} in
 * {@code mainagent-subagent-design.md} section 2.3 and is re-checked before
 * every delegation executes; it complements, never replaces, the direct
 * binding rule.
 *
 * <p>Real tenant and user authorization is wired once principal propagation
 * reaches the managed Agent entrypoints. Local CLI runs stay on
 * {@link #PERMIT_ALL}, which keeps direct binding as the only gate.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public interface AgentAuthorizationPolicy {

    /** Permits every principal; the placeholder used until real wiring lands. */
    AgentAuthorizationPolicy PERMIT_ALL = (principal, agentId) -> true;

    /**
     * Returns whether the principal may invoke the target Agent.
     *
     * @param principal identity of the invoking user
     * @param agentId   target Agent identifier
     * @return authorization decision
     */
    boolean isAuthorized(AgentPrincipal principal, String agentId);

    /**
     * Identity of the invoking user. Both fields are {@code null} for local
     * CLI runs that carry no tenant context.
     *
     * @param tenantId invoking tenant
     * @param userId   invoking user
     */
    record AgentPrincipal(String tenantId, String userId) {}
}
