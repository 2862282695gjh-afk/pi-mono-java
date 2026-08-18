/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 数据面节点心跳请求。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
public class HeartbeatRequestVO {
    @PositiveOrZero
    private int activeAgents;

    @PositiveOrZero
    private int queuedTasks;

    @PositiveOrZero
    private double cpuLoad;

    @PositiveOrZero
    private long memoryUsedMb;
}
