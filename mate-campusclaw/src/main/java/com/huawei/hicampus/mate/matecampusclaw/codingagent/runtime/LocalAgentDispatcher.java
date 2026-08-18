/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentSummary;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentBindingResolver.Verdict;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes one parent-to-child delegation edge, unifying every hop of the
 * chain: re-validate the target, derive the trusted {@link DelegationContext},
 * prepare the child runtime, and run the child through
 * {@link TransientAgentRunner}.
 *
 * <p>Validation always precedes execution and the context constructor
 * re-enforces the structural invariants (depth cap, ancestry, self-binding)
 * as defense in depth. Every hop is logged with parent, target, ancestry,
 * depth and invocationId so chains stay traceable before the audit-event
 * PR lands.
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
     * Returns the delegatable child candidates for one session's Agent.
     *
     * @param state delegation state of the asking session
     * @param parent delegating parent runtime
     * @return resolver-approved child summaries, empty when delegation is off
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
     * Executes one delegation edge made by the asking session's Agent.
     *
     * @param state delegation state of the asking session
     * @param parent delegating parent runtime
     * @param targetAgentId requested child Agent identifier
     * @param task self-contained task instructions for the child
     * @param fallbackModel model used when the child binds no default model
     * @return the child Agent's final answer text
     * @throws AgentRuntimeException when the target is rejected or the run fails
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
     * Returns the runtime manager shared by every session of the chain.
     *
     * @return the single runtime manager instance
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
