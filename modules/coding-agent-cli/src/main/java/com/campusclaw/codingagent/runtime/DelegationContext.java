/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Trusted context injected by the runtime for exactly one parent-to-child
 * delegation, following {@code mainagent-subagent-design.md} section 5.4.
 * Identity, permissions, parentage, ancestry, depth, deadline and the
 * effective toolset are runtime-controlled; the LLM can never override them.
 *
 * <p>Structural invariants enforced by the canonical constructor, which makes
 * an invalid delegation state unrepresentable:
 *
 * <ul>
 *   <li>{@code delegationDepth} stays within {@code 1..MAX_DELEGATION_DEPTH};</li>
 *   <li>{@code ancestryAgentIds} is immutable, duplicate-free and exactly as
 *       long as the delegation depth, ending with {@code parentAgentId};</li>
 *   <li>{@code targetAgentId} never appears in the ancestry, which also rules
 *       out self-binding because the parent closes the chain.</li>
 * </ul>
 *
 * <p>{@code tenantId}/{@code userId} may be {@code null} for local CLI runs.
 * The edge-scoped lifecycle identifiers ({@code parentAgentSessionId},
 * {@code parentRunId}, {@code subTaskId}, {@code idempotencyKey},
 * {@code deadline}) are {@code null} until the dispatcher and SubTask
 * lifecycle wire them in; the structural guarantees above already hold.
 *
 * @param tenantId             invoking tenant, {@code null} in local CLI runs
 * @param userId               invoking user, {@code null} in local CLI runs
 * @param conversationId       conversation the whole chain serves
 * @param parentAgentSessionId session identifier of the delegating parent
 * @param parentRunId          run identifier of the delegating parent
 * @param subTaskId            SubTask this delegation executes
 * @param invocationId         unique identifier of this delegation edge
 * @param parentAgentId        delegating parent Agent identifier
 * @param targetAgentId        delegated child Agent identifier
 * @param ancestryAgentIds     Agent ids already active, entry first, parent last
 * @param delegationDepth      depth of the target, 1 for the first delegation
 * @param idempotencyKey       idempotency key of this delegation edge
 * @param deadline             deadline of this delegation edge
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record DelegationContext(
        String tenantId,
        String userId,
        String conversationId,
        String parentAgentSessionId,
        String parentRunId,
        String subTaskId,
        String invocationId,
        String parentAgentId,
        String targetAgentId,
        List<String> ancestryAgentIds,
        int delegationDepth,
        String idempotencyKey,
        Instant deadline) {

    /** Hard delegation cap: entry depth 0, first delegation 1, second 2. */
    public static final int MAX_DELEGATION_DEPTH = 2;

    public DelegationContext {
        ancestryAgentIds = ancestryAgentIds == null ? List.of() : List.copyOf(ancestryAgentIds);
        requireNonBlank(conversationId, "conversationId");
        requireNonBlank(invocationId, "invocationId");
        requireNonBlank(parentAgentId, "parentAgentId");
        requireNonBlank(targetAgentId, "targetAgentId");
        if (delegationDepth < 1 || delegationDepth > MAX_DELEGATION_DEPTH) {
            throw new IllegalArgumentException("Delegation depth out of range: " + delegationDepth);
        }
        if (ancestryAgentIds.size() != delegationDepth) {
            throw new IllegalArgumentException(
                    "Ancestry length must equal delegation depth: " + ancestryAgentIds.size());
        }
        if (ancestryAgentIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Ancestry must not contain null agent ids");
        }
        if (new HashSet<>(ancestryAgentIds).size() != ancestryAgentIds.size()) {
            throw new IllegalArgumentException("Ancestry must not contain duplicate agent ids");
        }
        if (!parentAgentId.equals(ancestryAgentIds.getLast())) {
            throw new IllegalArgumentException("Ancestry must end with the parent agent id");
        }
        if (ancestryAgentIds.contains(targetAgentId)) {
            throw new IllegalArgumentException("Target agent must not appear in the ancestry");
        }
    }

    /**
     * Creates the context for the first delegation, made by the entry Agent.
     *
     * @param entryAgentId  entry Agent identifier, depth 0 by definition
     * @param targetAgentId delegated child Agent identifier
     * @param conversationId conversation the chain serves
     * @param invocationId  unique identifier of this delegation edge
     * @return context with ancestry {@code [entryAgentId]} and depth 1
     */
    public static DelegationContext forEntry(
            String entryAgentId, String targetAgentId, String conversationId, String invocationId) {
        return new DelegationContext(
                null,
                null,
                conversationId,
                null,
                null,
                null,
                invocationId,
                entryAgentId,
                targetAgentId,
                List.of(entryAgentId),
                1,
                null,
                null);
    }

    /**
     * Creates the context for the next delegation made by the Agent this
     * context describes. Identity fields are carried over; edge-scoped
     * lifecycle identifiers reset to {@code null} for the dispatcher to fill.
     *
     * @param nextTargetAgentId delegated child Agent identifier
     * @param nextInvocationId  unique identifier of the new delegation edge
     * @return context with extended ancestry and incremented depth
     * @throws IllegalStateException when the hard depth cap is already reached
     */
    public DelegationContext delegateTo(String nextTargetAgentId, String nextInvocationId) {
        if (!canDelegateFurther()) {
            throw new IllegalStateException("Delegation depth limit reached: " + delegationDepth);
        }
        List<String> extended = new ArrayList<>(ancestryAgentIds);
        extended.add(targetAgentId);
        return new DelegationContext(
                tenantId,
                userId,
                conversationId,
                null,
                null,
                null,
                nextInvocationId,
                targetAgentId,
                nextTargetAgentId,
                extended,
                delegationDepth + 1,
                null,
                null);
    }

    /**
     * Returns whether the Agent described by this context may delegate again.
     *
     * @return {@code true} while another delegation stays within the cap
     */
    public boolean canDelegateFurther() {
        return delegationDepth < MAX_DELEGATION_DEPTH;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
