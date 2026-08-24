/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.controlplane.domain;

/**
 * Live capacity / load metrics reported by a data-plane node on every heartbeat.
 *
 * <p>All fields are non-negative. The control plane uses these values to break ties in
 * scheduling and to surface fleet-wide utilisation in the management API.
 *
 * <p>The compact constructor enforces these invariants and throws
 * {@link IllegalArgumentException} on any violation. {@code cpuLoad} additionally rejects
 * {@link Double#NaN} and {@code ±Infinity} — non-finite values poison comparators that
 * the runtime scheduler uses for tie-breaking.
 *
 * @param activeAgents currently running Agent instances on the node
 * @param queuedTasks tasks waiting in the local task queue
 * @param cpuLoad     normalised 1-minute CPU load average; {@code [0.0, n_cpu]}
 * @param memoryUsedMb resident set size in megabytes (best-effort)
 * @version [br_eCampusCore 26.0.0, 2026/06/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record NodeMetrics(int activeAgents, int queuedTasks, double cpuLoad, long memoryUsedMb) {

    /**
     * Empty metrics tuple — used as the seed value when a node has just registered and
     * has not posted its first heartbeat yet.
     */
    public static final NodeMetrics EMPTY = new NodeMetrics(0, 0, 0.0d, 0L);

    public NodeMetrics {
        if (activeAgents < 0) {
            throw new IllegalArgumentException("activeAgents must be >= 0: " + activeAgents);
        }
        if (queuedTasks < 0) {
            throw new IllegalArgumentException("queuedTasks must be >= 0: " + queuedTasks);
        }
        if (!Double.isFinite(cpuLoad) || cpuLoad < 0.0d) {
            throw new IllegalArgumentException("cpuLoad must be a finite, non-negative double: " + cpuLoad);
        }
        if (memoryUsedMb < 0L) {
            throw new IllegalArgumentException("memoryUsedMb must be >= 0: " + memoryUsedMb);
        }
    }
}
