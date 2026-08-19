/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import com.campusclaw.agent.controlplane.domain.ScheduleDecision;

import lombok.Getter;

/**
 * Runtime 节点调度结果响应。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Getter
public class ScheduleRuntimeResponseVO {
    private final String nodeId;

    private final String host;

    private final int port;

    private final String reason;

    public ScheduleRuntimeResponseVO(ScheduleDecision decision) {
        this.nodeId = decision.nodeId();
        this.host = decision.host();
        this.port = decision.port();
        this.reason = decision.reason();
    }
}
