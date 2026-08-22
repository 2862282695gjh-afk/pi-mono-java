/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeMetrics;

import lombok.Getter;

/**
 * 节点最新负载指标响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class NodeMetricsResponseVO {
    private final int activeAgents;

    private final int queuedTasks;

    private final double cpuLoad;

    private final long memoryUsedMb;

    public NodeMetricsResponseVO(NodeMetrics metrics) {
        this.activeAgents = metrics.activeAgents();
        this.queuedTasks = metrics.queuedTasks();
        this.cpuLoad = metrics.cpuLoad();
        this.memoryUsedMb = metrics.memoryUsedMb();
    }
}
