/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

/**
 * Payload accepted by {@code POST /api/v1/nodes/{nodeId}/heartbeat}.
 *
 * <p>All fields are non-negative; {@code cpuLoad} additionally rejects {@code NaN} and
 * {@code ±Infinity}. Validation runs in the compact constructor — the in-process control
 * plane uses webflux {@code RouterFunction} routing rather than {@code @RestController},
 * so jakarta-validation annotations would need a separate validator wiring; the record
 * canonical-constructor invariants here are the single source of truth.
 *
 * @param activeAgents number of currently running Agent instances
 * @param queuedTasks  number of tasks waiting in the local queue
 * @param cpuLoad      normalised CPU load
 * @param memoryUsedMb resident set size in megabytes
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record HeartbeatRequest(int activeAgents, int queuedTasks, double cpuLoad, long memoryUsedMb) {

    public HeartbeatRequest {
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
