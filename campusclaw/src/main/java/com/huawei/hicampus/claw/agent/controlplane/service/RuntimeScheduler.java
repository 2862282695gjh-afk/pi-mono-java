/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.controlplane.service;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.controlplane.domain.NodeInfo;
import com.huawei.hicampus.claw.agent.controlplane.domain.NodeStatus;
import com.huawei.hicampus.claw.agent.controlplane.domain.RuntimeCapability;
import com.huawei.hicampus.claw.agent.controlplane.domain.ScheduleDecision;
import com.huawei.hicampus.claw.agent.controlplane.domain.ScheduleRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Selects the best data-plane node for a given {@link ScheduleRequest}.
 *
 * <p>Strategy:
 * <ol>
 *   <li>{@code preferredNodeId} wins if it exists and is ACTIVE — session affinity.</li>
 *   <li>Otherwise filter ACTIVE nodes by required capabilities, then pick the next
 *       candidate via round-robin on the ordered candidate list. Round-robin cursors are
 *       tracked per exact required-capability set.</li>
 * </ol>
 *
 * @version [br_eCampusCore 26.0.0, 2026/06/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeScheduler.class);

    private final NodeRegistry registry;

    private final ConcurrentMap<Set<RuntimeCapability>, AtomicInteger> roundRobinCursors = new ConcurrentHashMap<>();

    /**
     * Spring constructor.
     *
     * @param registry node registry providing live snapshots
     */
    public RuntimeScheduler(NodeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Picks a node for the given request.
     *
     * @param request scheduling parameters
     * @return decision describing the chosen node
     * @throws NoSuchElementException if no node is currently eligible
     */
    public ScheduleDecision schedule(ScheduleRequest request) {
        Optional<NodeInfo> sticky = stickyChoice(request);
        if (sticky.isPresent()) {
            NodeInfo node = sticky.get();
            log.debug("scheduler picked sticky node: nodeId={}", node.nodeId());
            return decisionFor(node, "affinity");
        }
        List<NodeInfo> candidates = filterCandidates(request);
        if (candidates.isEmpty()) {
            throw new NoSuchElementException("no eligible node for capabilities: " + request.requiredCapabilities());
        }
        NodeInfo selected = pickRoundRobin(candidates, request.requiredCapabilities());
        log.debug("scheduler picked node: nodeId={} candidates={}", selected.nodeId(), candidates.size());
        return decisionFor(selected, "round-robin");
    }

    private Optional<NodeInfo> stickyChoice(ScheduleRequest request) {
        if (request.preferredNodeId() == null || request.preferredNodeId().isBlank()) {
            return Optional.empty();
        }
        return registry.findNode(request.preferredNodeId())
                .filter(node -> node.status() == NodeStatus.ACTIVE)
                .filter(node -> node.capabilities().containsAll(request.requiredCapabilities()));
    }

    private List<NodeInfo> filterCandidates(ScheduleRequest request) {
        return registry.listAll().stream()
                .filter(node -> node.status() == NodeStatus.ACTIVE)
                .filter(node -> node.capabilities().containsAll(request.requiredCapabilities()))
                .sorted(Comparator.comparing(NodeInfo::nodeId))
                .toList();
    }

    private NodeInfo pickRoundRobin(List<NodeInfo> candidates, Set<RuntimeCapability> requiredCapabilities) {
        Set<RuntimeCapability> key = Set.copyOf(requiredCapabilities);
        AtomicInteger cursor = roundRobinCursors.computeIfAbsent(key, ignored -> new AtomicInteger(0));
        int index = Math.floorMod(cursor.getAndIncrement(), candidates.size());
        return candidates.get(index);
    }

    private ScheduleDecision decisionFor(NodeInfo node, String reason) {
        return new ScheduleDecision(node.nodeId(), node.host(), node.port(), reason);
    }
}
