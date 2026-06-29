/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Set;

import com.campusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.domain.RuntimeCapability;
import com.campusclaw.agent.controlplane.domain.ScheduleDecision;
import com.campusclaw.agent.controlplane.domain.ScheduleRequest;

import org.junit.jupiter.api.Test;

class RuntimeSchedulerTest {

    private final ControlPlaneProperties properties = new ControlPlaneProperties(null);

    @Test
    void schedulePicksAffinityWhenAvailable() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        NodeInfo a = registry.register("host-a", 9001, "1.0.0", Set.of(RuntimeCapability.MODEL_OPENAI));
        registry.register("host-b", 9002, "1.0.0", Set.of(RuntimeCapability.MODEL_OPENAI));

        ScheduleRequest request = new ScheduleRequest(Set.of(RuntimeCapability.MODEL_OPENAI), a.nodeId());
        ScheduleDecision decision = scheduler.schedule(request);

        assertEquals(a.nodeId(), decision.nodeId());
        assertEquals("affinity", decision.reason());
    }

    @Test
    void scheduleFallsBackToRoundRobinWhenNoAffinity() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        NodeInfo a = registry.register("host-a", 9001, "1.0.0", Set.of(RuntimeCapability.TOOL_BASH));
        NodeInfo b = registry.register("host-b", 9002, "1.0.0", Set.of(RuntimeCapability.TOOL_BASH));

        ScheduleRequest req = new ScheduleRequest(Set.of(RuntimeCapability.TOOL_BASH), null);
        ScheduleDecision first = scheduler.schedule(req);
        ScheduleDecision second = scheduler.schedule(req);

        assertEquals("round-robin", first.reason());
        assertEquals("round-robin", second.reason());
        assertNotEquals(first.nodeId(), second.nodeId());
        Set<String> seen = Set.of(first.nodeId(), second.nodeId());
        assertEquals(Set.of(a.nodeId(), b.nodeId()), seen);
    }

    @Test
    void scheduleSkipsNodesMissingRequiredCapability() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        registry.register("host-a", 9001, "1.0.0", Set.of(RuntimeCapability.TOOL_BASH));
        NodeInfo b = registry.register("host-b", 9002, "1.0.0", Set.of(RuntimeCapability.SUBAGENT_MCP));

        ScheduleRequest req = new ScheduleRequest(Set.of(RuntimeCapability.SUBAGENT_MCP), null);
        ScheduleDecision decision = scheduler.schedule(req);

        assertEquals(b.nodeId(), decision.nodeId());
    }

    @Test
    void scheduleFallsBackWhenPreferredNodeLacksRequiredCapability() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        NodeInfo preferred = registry.register("host-a", 9001, "1.0.0", Set.of(RuntimeCapability.TOOL_BASH));
        NodeInfo fallback = registry.register("host-b", 9002, "1.0.0", Set.of(RuntimeCapability.MODEL_OPENAI));

        ScheduleRequest req = new ScheduleRequest(Set.of(RuntimeCapability.MODEL_OPENAI), preferred.nodeId());
        ScheduleDecision decision = scheduler.schedule(req);

        assertEquals(fallback.nodeId(), decision.nodeId());
        assertEquals("round-robin", decision.reason());
    }

    @Test
    void scheduleRaisesWhenNoEligibleNode() {
        NodeRegistry registry = new NodeRegistry(properties, Clock.systemUTC());
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        registry.register("host-a", 9001, "1.0.0", Set.of(RuntimeCapability.TOOL_BASH));

        ScheduleRequest req = new ScheduleRequest(Set.of(RuntimeCapability.MODEL_MISTRAL), null);

        assertThrows(NoSuchElementException.class, () -> scheduler.schedule(req));
    }
}
