/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeInfo;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeStatus;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.ScheduleDecision;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.ScheduleRequest;

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
 *       candidate via round-robin on the ordered candidate list.</li>
 * </ol>
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeScheduler.class);

    private final NodeRegistry registry;

    private final AtomicInteger roundRobinCursor = new AtomicInteger(0);

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
        NodeInfo selected = pickRoundRobin(candidates);
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
                .sorted((a, b) -> a.nodeId().compareTo(b.nodeId()))
                .toList();
    }

    private NodeInfo pickRoundRobin(List<NodeInfo> candidates) {
        int index = Math.floorMod(roundRobinCursor.getAndIncrement(), candidates.size());
        return candidates.get(index);
    }

    private ScheduleDecision decisionFor(NodeInfo node, String reason) {
        return new ScheduleDecision(node.nodeId(), node.host(), node.port(), reason);
    }
}
