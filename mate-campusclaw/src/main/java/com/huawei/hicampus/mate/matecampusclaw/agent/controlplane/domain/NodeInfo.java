/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable snapshot of a registered data-plane node held by the control plane registry.
 *
 * <p>This MR ships the data model only — there is no {@code NodeRegistry} yet, so
 * snapshots are not persisted or mutated by any service. {@link #withHeartbeat} and
 * {@link #withStatus} return new copies but no caller wires them up until MR-B.
 *
 * <p>The compact constructor enforces the following invariants and throws
 * {@link IllegalArgumentException} on any violation: {@code nodeId}, {@code host} and
 * {@code version} non-blank; {@code port} in {@code [1, 65535]}; {@code status},
 * {@code registeredAt}, {@code lastHeartbeatAt}, {@code metrics} non-null; null
 * {@code capabilities} is normalised to an empty set, otherwise defensively copied.
 *
 * @param nodeId          unique identifier minted by the control plane on registration
 * @param host            advertised host or DNS name reachable from the control plane
 * @param port            advertised data-plane HTTP/SSE port
 * @param version         runtime version string (e.g. "1.0.0")
 * @param capabilities    coarse-grained capability tags supported by this node
 * @param status          current lifecycle status
 * @param registeredAt    instant the node first registered
 * @param lastHeartbeatAt instant of the most recent heartbeat
 * @param metrics         most recent metrics snapshot reported with the heartbeat
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record NodeInfo(
        String nodeId,
        String host,
        int port,
        String version,
        Set<RuntimeCapability> capabilities,
        NodeStatus status,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        NodeMetrics metrics) {

    public NodeInfo {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (registeredAt == null) {
            throw new IllegalArgumentException("registeredAt must not be null");
        }
        if (lastHeartbeatAt == null) {
            throw new IllegalArgumentException("lastHeartbeatAt must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
    }

    /**
     * Returns a copy of this node with an updated heartbeat instant and metrics; status is
     * forced to {@link NodeStatus#ACTIVE} since a heartbeat means the node is healthy.
     *
     * @param at      heartbeat instant
     * @param updated freshly observed metrics
     * @return a new {@code NodeInfo} sharing all immutable fields with this one
     */
    public NodeInfo withHeartbeat(Instant at, NodeMetrics updated) {
        return new NodeInfo(nodeId, host, port, version, capabilities, NodeStatus.ACTIVE, registeredAt, at, updated);
    }

    /**
     * Returns a copy of this node with the supplied lifecycle status.
     *
     * @param newStatus next status (typically STALE or DEREGISTERED)
     * @return a new {@code NodeInfo} sharing all other fields with this one
     */
    public NodeInfo withStatus(NodeStatus newStatus) {
        return new NodeInfo(
                nodeId, host, port, version, capabilities, newStatus, registeredAt, lastHeartbeatAt, metrics);
    }
}
