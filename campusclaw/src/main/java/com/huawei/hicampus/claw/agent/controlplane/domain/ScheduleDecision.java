/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.controlplane.domain;

/**
 * Result of a successful scheduling decision returned by {@code RuntimeScheduler}.
 *
 * <p>Carries both the chosen node id and a structured reason — useful for diagnostics in
 * load-balancer dashboards and integration tests.
 *
 * @param nodeId  identifier of the selected data-plane node
 * @param host    host of the selected node, copied verbatim from the registry snapshot
 * @param port    port of the selected node, copied verbatim from the registry snapshot
 * @param reason  short machine-readable token describing why this node won
 *                (e.g. "affinity", "round-robin")
 * @version [br_eCampusCore 26.0.0, 2026/06/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record ScheduleDecision(String nodeId, String host, int port, String reason) {

    public ScheduleDecision {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
