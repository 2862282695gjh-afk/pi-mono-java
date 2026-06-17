/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NodeInfo} compact-constructor invariants and {@code with*} helpers.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class NodeInfoTest {

    @Test
    void factoryAcceptsValidPayload() {
        NodeMetrics metrics = new NodeMetrics(2, 1, 0.5d, 256L);
        NodeInfo info = new NodeInfo(
                "node-test",
                "10.0.0.1",
                9001,
                "1.0.0",
                Set.of(RuntimeCapability.MODEL_OPENAI),
                NodeStatus.ACTIVE,
                Instant.now(),
                Instant.now(),
                metrics);

        assertEquals(NodeStatus.ACTIVE, info.status());
        assertEquals(2, info.metrics().activeAgents());
        assertEquals(NodeStatus.STALE, info.withStatus(NodeStatus.STALE).status());
    }

    @Test
    void rejectsBlankIdentifiers() {
        Instant now = Instant.now();
        NodeMetrics metrics = NodeMetrics.EMPTY;
        Set<RuntimeCapability> caps = Set.of();

        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("  ", "10.0.0.1", 9001, "1.0.0", caps, NodeStatus.ACTIVE, now, now, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "", 9001, "1.0.0", caps, NodeStatus.ACTIVE, now, now, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 9001, "  ", caps, NodeStatus.ACTIVE, now, now, metrics));
    }

    @Test
    void rejectsOutOfRangePort() {
        Instant now = Instant.now();
        NodeMetrics metrics = NodeMetrics.EMPTY;
        Set<RuntimeCapability> caps = Set.of();

        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 0, "1.0.0", caps, NodeStatus.ACTIVE, now, now, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 70_000, "1.0.0", caps, NodeStatus.ACTIVE, now, now, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", -1, "1.0.0", caps, NodeStatus.ACTIVE, now, now, metrics));
    }

    @Test
    void rejectsNullRequiredFields() {
        Instant now = Instant.now();
        NodeMetrics metrics = NodeMetrics.EMPTY;
        Set<RuntimeCapability> caps = Set.of();

        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 9001, "1.0.0", caps, null, now, now, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 9001, "1.0.0", caps, NodeStatus.ACTIVE, null, now, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 9001, "1.0.0", caps, NodeStatus.ACTIVE, now, null, metrics));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeInfo("node-x", "10.0.0.1", 9001, "1.0.0", caps, NodeStatus.ACTIVE, now, now, null));
    }
}
