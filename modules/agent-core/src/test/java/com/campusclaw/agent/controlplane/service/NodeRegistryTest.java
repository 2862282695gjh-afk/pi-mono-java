/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Set;

import com.campusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.domain.NodeMetrics;
import com.campusclaw.agent.controlplane.domain.NodeStatus;
import com.campusclaw.agent.controlplane.domain.RuntimeCapability;

import org.junit.jupiter.api.Test;

class NodeRegistryTest {

    private final ControlPlaneProperties properties = new ControlPlaneProperties(null);

    @Test
    void registerAssignsIdAndKeepsNodeActive() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());

        NodeInfo registered = registry.register("host-a", 9001, "1.0.0", Set.of(RuntimeCapability.MODEL_OPENAI));

        assertEquals(NodeStatus.ACTIVE, registered.status());
        assertEquals("host-a", registered.host());
        assertEquals(9001, registered.port());
        assertTrue(registered.nodeId().startsWith("node-"));
        assertEquals(1, registry.listAll().size());
    }

    @Test
    void heartbeatRefreshesMetricsAndStatus() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-18T00:00:00Z"));
        NodeRegistry registry = new NodeRegistry(properties, clock);
        NodeInfo registered = registry.register("host-b", 9002, "1.0.0", Set.of(RuntimeCapability.TOOL_BASH));

        clock.advanceSeconds(5L);
        NodeMetrics metrics = new NodeMetrics(3, 1, 0.42d, 256L);
        NodeInfo refreshed = registry.heartbeat(registered.nodeId(), metrics);

        assertEquals(NodeStatus.ACTIVE, refreshed.status());
        assertEquals(3, refreshed.metrics().activeAgents());
        assertEquals(256L, refreshed.metrics().memoryUsedMb());
    }

    @Test
    void heartbeatForUnknownNodeRaises() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());

        NoSuchElementException ex =
                assertThrows(NoSuchElementException.class, () -> registry.heartbeat("node-missing", NodeMetrics.EMPTY));
        assertTrue(ex.getMessage().contains("node-missing"));
    }

    @Test
    void deregisterRemovesNode() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());
        NodeInfo registered = registry.register("host-c", 9003, "1.0.0", Set.of());

        boolean removed = registry.deregister(registered.nodeId());

        assertTrue(removed);
        assertTrue(registry.listAll().isEmpty());
        assertFalse(registry.deregister(registered.nodeId()));
    }

    @Test
    void sweepDemotesActiveToStaleAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-18T00:00:00Z"));
        NodeRegistry registry = new NodeRegistry(properties, clock);
        NodeInfo registered = registry.register("host-d", 9004, "1.0.0", Set.of());

        clock.advanceSeconds(31L);
        int transitions = registry.sweep();

        assertEquals(1, transitions);
        assertEquals(
                NodeStatus.STALE,
                registry.findNode(registered.nodeId()).orElseThrow().status());
    }

    @Test
    void sweepRemovesStaleAfterGracePeriod() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-18T00:00:00Z"));
        NodeRegistry registry = new NodeRegistry(properties, clock);
        NodeInfo registered = registry.register("host-e", 9005, "1.0.0", Set.of());

        clock.advanceSeconds(31L);
        registry.sweep();
        clock.advanceSeconds(60L * 5L + 1L);
        int transitions = registry.sweep();

        assertEquals(1, transitions);
        assertTrue(registry.findNode(registered.nodeId()).isEmpty());
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
