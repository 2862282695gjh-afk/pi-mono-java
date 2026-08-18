/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.campusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;

/**
 * Computes the effective child Agent set for one parent Agent following
 * {@code mainagent-subagent-design.md} sections 2.3 and 5.1:
 *
 * <pre>
 * effectiveChildAgents = parentAgent.bindingAgents
 *         ∩ enabledAgents
 *         ∩ principalAuthorizedAgents
 *         - ancestryAgents
 * </pre>
 *
 * <p>Candidates come exclusively from the parent's local snapshot bindings;
 * the parent never reads a global Agent directory. {@link #resolve} builds the
 * lightweight summaries for the {@code invoke_agent} tool description, and
 * {@link #validate} re-checks every rule before a delegation executes. The
 * resolver never trusts prompt content, and unknown child metadata fails
 * closed.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class AgentBindingResolver {

    private final ChildAgentMetadataSource metadataSource;
    private final AgentAuthorizationPolicy authorizationPolicy;

    /**
     * Creates a resolver over a child metadata source and an authorization policy.
     *
     * @param metadataSource      loads child Agent metadata, local-first
     * @param authorizationPolicy decides principal access to target Agents
     */
    public AgentBindingResolver(ChildAgentMetadataSource metadataSource, AgentAuthorizationPolicy authorizationPolicy) {
        this.metadataSource = Objects.requireNonNull(metadataSource);
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy);
    }

    /**
     * Returns the child Agents offered to the parent in the current chain.
     * Bindings that are blank, duplicated, self-referencing, already active in
     * the chain, unknown, disabled, version-incompatible or unauthorized are
     * silently filtered; use {@link #validate} for an explained decision.
     *
     * @param parent          delegating parent runtime
     * @param principal       identity of the invoking user
     * @param invocationChain agent ids already active, including the parent
     * @return immutable summaries for the {@code invoke_agent} description
     */
    public List<ChildAgentSummary> resolve(
            PreparedAgentRuntime parent, AgentPrincipal principal, List<String> invocationChain) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(principal);
        List<String> chain = invocationChain == null ? List.of() : List.copyOf(invocationChain);
        List<ChildAgentSummary> summaries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AgentReference binding : parent.bindingAgents()) {
            String childId = binding.id();
            if (childId == null || childId.isBlank() || !seen.add(childId)) {
                continue;
            }
            if (parent.agentId().equals(childId) || chain.contains(childId)) {
                continue;
            }
            Verdict candidate = verdict(parent, principal, chain, childId);
            if (candidate instanceof Allowed allowed) {
                summaries.add(allowed.child());
            }
        }
        return List.copyOf(summaries);
    }

    /**
     * Re-checks one delegation before execution, combining the depth cap with
     * the same rules {@link #resolve} applies per candidate.
     *
     * @param parent          delegating parent runtime
     * @param principal       identity of the invoking user
     * @param invocationChain agent ids already active, including the parent
     * @param parentDepth     depth of the parent, 0 for the entry Agent
     * @param targetAgentId   requested child Agent identifier
     * @return allowed summary or an explained rejection
     * @throws IllegalArgumentException when {@code parentDepth} is outside
     *         {@code 0..MAX_DELEGATION_DEPTH}
     */
    public Verdict validate(
            PreparedAgentRuntime parent,
            AgentPrincipal principal,
            List<String> invocationChain,
            int parentDepth,
            String targetAgentId) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(principal);
        Objects.requireNonNull(targetAgentId);
        if (parentDepth < 0 || parentDepth > DelegationContext.MAX_DELEGATION_DEPTH) {
            throw new IllegalArgumentException("Parent depth out of range: " + parentDepth);
        }
        if (parentDepth + 1 > DelegationContext.MAX_DELEGATION_DEPTH) {
            return new Rejected(Rejection.DEPTH_EXCEEDED, "parent depth " + parentDepth);
        }
        List<String> chain = invocationChain == null ? List.of() : List.copyOf(invocationChain);
        return verdict(parent, principal, chain, targetAgentId);
    }

    private Verdict verdict(
            PreparedAgentRuntime parent, AgentPrincipal principal, List<String> chain, String targetAgentId) {
        Optional<AgentReference> bound = parent.bindingAgents().stream()
                .filter(binding -> targetAgentId.equals(binding.id()))
                .findFirst();
        if (bound.isEmpty()) {
            return new Rejected(Rejection.NOT_DIRECTLY_BOUND, targetAgentId);
        }
        if (parent.agentId().equals(targetAgentId)) {
            return new Rejected(Rejection.SELF_BINDING, targetAgentId);
        }
        if (chain.contains(targetAgentId)) {
            return new Rejected(Rejection.IN_ANCESTRY, targetAgentId);
        }
        Optional<ChildAgentMetadata> metadata = metadataSource.load(targetAgentId);
        if (metadata.isEmpty()) {
            return new Rejected(Rejection.UNKNOWN_CHILD, targetAgentId);
        }
        ChildAgentMetadata child = metadata.get();

        // CampusMate guarantees bindingAgents only references agents enabled at query
        // time; this re-check of the child's CURRENT state defends cached parent
        // snapshots whose child was disabled after materialization.
        if (!child.enabled()) {
            return new Rejected(Rejection.NOT_ENABLED, targetAgentId);
        }
        if (versionIncompatible(bound.get(), child)) {
            return new Rejected(
                    Rejection.VERSION_MISMATCH, "binding " + bound.get().version() + " vs child " + child.version());
        }
        if (!authorizationPolicy.isAuthorized(principal, targetAgentId)) {
            return new Rejected(Rejection.NOT_AUTHORIZED, targetAgentId);
        }
        return new Allowed(new ChildAgentSummary(
                child.agentId(),
                bound.get().name(),
                bound.get().displayName(),
                bound.get().description(),
                child.version()));
    }

    private static boolean versionIncompatible(AgentReference binding, ChildAgentMetadata child) {
        String pinned = binding.version();
        if (pinned == null || pinned.isBlank()) {
            return false;
        }
        return child.version() == null || !pinned.equals(child.version());
    }

    /**
     * Lightweight child description embedded in the {@code invoke_agent} tool
     * description. The version reports the child's actual metadata, not the
     * parent's binding pin.
     *
     * @param agentId     child Agent identifier
     * @param name        binding name
     * @param displayName binding display name
     * @param description binding description shown to the model
     * @param version     child's actual version
     */
    record ChildAgentSummary(String agentId, String name, String displayName, String description, String version) {}

    /** Metadata the resolver needs about one child Agent. */
    record ChildAgentMetadata(String agentId, String version, boolean enabled) {}

    /** Loads child Agent metadata without materializing full runtimes. */
    @FunctionalInterface
    interface ChildAgentMetadataSource {

        /**
         * Returns metadata for one child Agent.
         *
         * @param agentId child Agent identifier
         * @return metadata, empty when the child cannot be resolved
         */
        Optional<ChildAgentMetadata> load(String agentId);
    }

    /** Outcome of validating one delegation. */
    sealed interface Verdict permits Allowed, Rejected {}

    /**
     * The delegation passed every rule.
     *
     * @param child summary of the validated child
     */
    record Allowed(ChildAgentSummary child) implements Verdict {}

    /**
     * The delegation violated one rule.
     *
     * @param reason violated rule
     * @param detail English diagnostic detail
     */
    record Rejected(Rejection reason, String detail) implements Verdict {}

    /** Rules a delegation can violate. */
    enum Rejection {
        NOT_DIRECTLY_BOUND,
        SELF_BINDING,
        IN_ANCESTRY,
        DEPTH_EXCEEDED,
        UNKNOWN_CHILD,
        NOT_ENABLED,
        VERSION_MISMATCH,
        NOT_AUTHORIZED
    }
}
