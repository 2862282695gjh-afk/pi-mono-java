/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeInfo;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeMetrics;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeStatus;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory registry of data-plane nodes maintained by the control plane.
 *
 * <p>Thread-safe — backed by a {@link ConcurrentHashMap} keyed on {@code nodeId}. The
 * registry is a single source of truth for liveness; persistence (etcd / Postgres) is a
 * deferred extension recorded in {@code docs/DEFERRED.md}.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final ConcurrentMap<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    private final ControlPlaneProperties properties;

    private final Clock clock;

    /**
     * Spring constructor. The {@link Clock} parameter is satisfied by a default UTC bean
     * registered in {@code AgentControlPlaneApplication} unless overridden.
     *
     * @param properties control plane configuration root
     * @param clock      time source; tests typically pass a fixed clock for deterministic TTL testing
     */
    public NodeRegistry(ControlPlaneProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a fresh data-plane node and assigns it an internal id.
     *
     * @param host         host advertised by the node
     * @param port         port advertised by the node
     * @param version      runtime version string
     * @param capabilities capability tags supported by the node
     * @return the newly-created node snapshot, including the assigned {@code nodeId}
     */
    public NodeInfo register(String host, int port, String version, Set<RuntimeCapability> capabilities) {
        Instant now = Instant.now(clock);
        NodeInfo info = new NodeInfo(
                "node-" + UUID.randomUUID(),
                host,
                port,
                version,
                capabilities,
                NodeStatus.ACTIVE,
                now,
                now,
                NodeMetrics.EMPTY);
        nodes.put(info.nodeId(), info);
        log.info("node registered: nodeId={} host={} port={} caps={}", info.nodeId(), host, port, capabilities);
        return info;
    }

    /**
     * Records a heartbeat for an existing node, refreshing its last-seen instant and
     * metrics.
     *
     * @param nodeId  identifier returned by {@link #register}
     * @param metrics most recent metrics snapshot reported by the node
     * @return the refreshed node snapshot
     * @throws NoSuchElementException if the node is unknown or already deregistered
     */
    public NodeInfo heartbeat(String nodeId, NodeMetrics metrics) {
        Instant now = Instant.now(clock);
        NodeInfo updated = nodes.computeIfPresent(nodeId, (id, existing) -> existing.withHeartbeat(now, metrics));
        if (updated == null) {
            throw new NoSuchElementException("node not registered: " + nodeId);
        }
        log.debug("heartbeat: nodeId={} active={}", nodeId, metrics.activeAgents());
        return updated;
    }

    /**
     * Removes a node from the registry — typically called on graceful shutdown.
     *
     * @param nodeId identifier returned by {@link #register}
     * @return {@code true} if a node was removed, {@code false} if no such node existed
     */
    public boolean deregister(String nodeId) {
        NodeInfo removed = nodes.remove(nodeId);
        if (removed != null) {
            log.info("node deregistered: nodeId={}", nodeId);
            return true;
        }
        return false;
    }

    /**
     * Returns a snapshot of a single node, if known.
     *
     * @param nodeId identifier of interest
     * @return the node snapshot, or {@link Optional#empty()} if unknown
     */
    public Optional<NodeInfo> findNode(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    /**
     * Returns an unmodifiable list snapshot of all currently tracked nodes.
     *
     * @return list of node snapshots; never {@code null}
     */
    public List<NodeInfo> listAll() {
        Collection<NodeInfo> copy = nodes.values();
        return List.copyOf(copy);
    }

    /**
     * Marks any node whose last heartbeat is older than the configured TTL as STALE, and
     * removes nodes that have been STALE longer than the grace period.
     *
     * <p>Called periodically by {@code HealthCheckScheduler}; invoked directly from tests.
     *
     * @return number of state transitions applied during this sweep
     */
    public int sweep() {
        Instant now = Instant.now(clock);
        Duration ttl = properties.heartbeat().ttl();
        Duration grace = properties.heartbeat().graceAfterStale();
        int transitions = 0;
        for (NodeInfo node : nodes.values()) {
            Duration sinceHeartbeat = Duration.between(node.lastHeartbeatAt(), now);
            transitions += applySweepTransition(node, sinceHeartbeat, ttl, grace);
        }
        return transitions;
    }

    private int applySweepTransition(NodeInfo node, Duration sinceHeartbeat, Duration ttl, Duration grace) {
        if (node.status() == NodeStatus.ACTIVE && sinceHeartbeat.compareTo(ttl) > 0) {
            nodes.replace(node.nodeId(), node, node.withStatus(NodeStatus.STALE));
            log.warn("node marked STALE: nodeId={} sinceHeartbeatSec={}", node.nodeId(), sinceHeartbeat.toSeconds());
            return 1;
        }
        if (node.status() == NodeStatus.STALE && sinceHeartbeat.compareTo(ttl.plus(grace)) > 0) {
            nodes.remove(node.nodeId(), node);
            log.warn("node removed after grace period: nodeId={}", node.nodeId());
            return 1;
        }
        return 0;
    }
}
