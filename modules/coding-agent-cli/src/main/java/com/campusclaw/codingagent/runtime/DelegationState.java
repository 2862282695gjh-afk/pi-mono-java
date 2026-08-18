/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.List;
import java.util.Objects;

import com.campusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;

/**
 * Session-scoped delegation state: everything one session needs to expose
 * {@code invoke_agent} and to execute a delegation edge through
 * {@link LocalAgentDispatcher}.
 *
 * <p>The entry session carries {@code selfContext == null} (depth 0 by
 * definition); a delegated child session carries the {@link DelegationContext}
 * that created it, from which depth, ancestry and the further-delegation cap
 * derive.
 *
 * @param dispatcher      execution dispatcher shared by the whole chain
 * @param conversationId  conversation the whole chain serves
 * @param principal       identity of the invoking user, nullable locally
 * @param selfContext     context that created this session, null for entry
 * @param wiring          entry-session collaborators for child assembly
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
     * Creates the state of an entry session (depth 0, no parent edge).
     *
     * @param dispatcher     execution dispatcher
     * @param conversationId conversation identifier, nullable in CLI runs
     * @param principal      invoking user, nullable in CLI runs
     * @param wiring         entry-session collaborators
     * @return entry delegation state
     */
    public static DelegationState entry(
            LocalAgentDispatcher dispatcher, String conversationId, AgentPrincipal principal, DelegationWiring wiring) {
        return new DelegationState(dispatcher, conversationId, principal, null, wiring);
    }

    /**
     * Derives the state of the child session created by the given context.
     *
     * @param parent parent state whose dispatcher, identity and wiring carry over
     * @param childContext context describing the child edge
     * @return child delegation state
     */
    public static DelegationState childOf(DelegationState parent, DelegationContext childContext) {
        return new DelegationState(
                parent.dispatcher(), parent.conversationId(), parent.principal(), childContext, parent.wiring());
    }

    /**
     * Depth of the session's own Agent: 0 for the entry Agent, otherwise the
     * depth recorded in the creating context.
     *
     * @return delegation depth of this session's Agent
     */
    public int depth() {
        return selfContext == null ? 0 : selfContext.delegationDepth();
    }

    /**
     * Returns whether this session's Agent may delegate at all: entry Agents
     * always may, delegated Agents only below the hard depth cap.
     *
     * @return true while another delegation stays within the cap
     */
    public boolean canDelegate() {
        return selfContext == null || selfContext.canDelegateFurther();
    }

    /**
     * Agent ids already active in the chain, including this session's Agent.
     *
     * @param selfAgentId this session's Agent identifier
     * @return immutable chain, entry-first, this Agent last
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
