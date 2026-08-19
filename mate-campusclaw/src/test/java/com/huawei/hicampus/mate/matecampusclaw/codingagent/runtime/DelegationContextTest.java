/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DelegationContextTest {

    @Test
    void entryContextCarriesSingleAgentAncestryAtDepthOne() {
        DelegationContext context = DelegationContext.forEntry("agent-1", "agent-2", "conv-1", "inv-1");

        assertEquals(List.of("agent-1"), context.ancestryAgentIds());
        assertEquals("agent-1", context.parentAgentId());
        assertEquals("agent-2", context.targetAgentId());
        assertEquals(1, context.delegationDepth());
        assertTrue(context.canDelegateFurther());
    }

    @Test
    void delegateToExtendsAncestryAndIncrementsDepth() {
        DelegationContext second = DelegationContext.forEntry("agent-1", "agent-2", "conv-1", "inv-1")
                .delegateTo("agent-3", "inv-2");

        assertEquals(List.of("agent-1", "agent-2"), second.ancestryAgentIds());
        assertEquals("agent-2", second.parentAgentId());
        assertEquals("agent-3", second.targetAgentId());
        assertEquals(2, second.delegationDepth());
        assertEquals("conv-1", second.conversationId());
        assertFalse(second.canDelegateFurther());
    }

    @Test
    void delegateFromDepthTwoIsRejectedAsHardCap() {
        DelegationContext second = DelegationContext.forEntry("agent-1", "agent-2", "conv-1", "inv-1")
                .delegateTo("agent-3", "inv-2");

        assertThrows(IllegalStateException.class, () -> second.delegateTo("agent-4", "inv-3"));
    }

    @Test
    void constructorRejectsDepthOutsideHardCap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext(
                        null,
                        null,
                        "conv-1",
                        null,
                        null,
                        null,
                        "inv-1",
                        "agent-1",
                        "agent-2",
                        List.of("agent-1"),
                        0,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext(
                        null,
                        null,
                        "conv-1",
                        null,
                        null,
                        null,
                        "inv-1",
                        "agent-1",
                        "agent-2",
                        List.of("agent-1"),
                        DelegationContext.MAX_DELEGATION_DEPTH + 1,
                        null,
                        null));
    }

    @Test
    void constructorRejectsAncestryNotMatchingDepthOrParent() {
        DelegationContextFactory factory = new DelegationContextFactory();

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(List.of("agent-1", "agent-2"), 1, "agent-2", "agent-3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(List.of("agent-1", "agent-2"), 2, "agent-1", "agent-3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(List.of("agent-1", "agent-1"), 2, "agent-1", "agent-3"));
    }

    @Test
    void constructorRejectsTargetInsideAncestryOrBlankIdentity() {
        DelegationContextFactory factory = new DelegationContextFactory();

        assertThrows(IllegalArgumentException.class, () -> factory.create(List.of("agent-1"), 1, "agent-1", "agent-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(List.of("agent-1", "agent-2"), 2, "agent-2", "agent-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext(
                        null,
                        null,
                        " ",
                        null,
                        null,
                        null,
                        "inv-1",
                        "agent-1",
                        "agent-2",
                        List.of("agent-1"),
                        1,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext(
                        null,
                        null,
                        "conv-1",
                        null,
                        null,
                        null,
                        null,
                        "agent-1",
                        "agent-2",
                        List.of("agent-1"),
                        1,
                        null,
                        null));
    }

    /** Small helper keeping the thirteen-component constructor readable. */
    private record DelegationContextFactory() {

        DelegationContext create(List<String> ancestry, int depth, String parent, String target) {
            return new DelegationContext(
                    null, null, "conv-1", null, null, null, "inv-1", parent, target, ancestry, depth, null, null);
        }
    }
}
