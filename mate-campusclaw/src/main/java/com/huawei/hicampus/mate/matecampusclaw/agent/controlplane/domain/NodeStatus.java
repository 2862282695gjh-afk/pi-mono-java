/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain;

/**
 * Lifecycle status of a data-plane node tracked by the control plane.
 *
 * <p><b>This MR introduces the data model only; state transitions are wired in MR-B
 * (NodeRegistry + sweep loop).</b> The intended state machine, for context:
 *
 * <ul>
 *   <li>{@link #ACTIVE} — node has registered and emitted a recent heartbeat;
 *   eligible for scheduling.</li>
 *   <li>{@link #STALE} — node has missed heartbeats for longer than the configured
 *   TTL (set by {@code NodeRegistry.sweep()} in MR-B).</li>
 *   <li>{@link #DEREGISTERED} — node has been graceful-shutdown via the deregister
 *   endpoint or torn down by sweep after a grace period (also MR-B).</li>
 * </ul>
 *
 * <p>In this MR the only mutator that exists is {@link NodeInfo#withStatus(NodeStatus)};
 * it does not enforce any transition rule — callers are responsible for legality once
 * the registry layer wires it up.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public enum NodeStatus {
    ACTIVE,
    STALE,
    DEREGISTERED
}
