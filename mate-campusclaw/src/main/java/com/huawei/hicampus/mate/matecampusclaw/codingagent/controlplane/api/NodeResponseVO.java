/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import java.time.Instant;
import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeInfo;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeStatus;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability;

import lombok.Getter;

/**
 * 已注册数据面节点响应。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Getter
public class NodeResponseVO {
    private final String nodeId;

    private final String host;

    private final int port;

    private final String version;

    private final Set<RuntimeCapability> capabilities;

    private final NodeStatus status;

    private final Instant registeredAt;

    private final Instant lastHeartbeatAt;

    private final NodeMetricsResponseVO metrics;

    public NodeResponseVO(NodeInfo node) {
        this.nodeId = node.nodeId();
        this.host = node.host();
        this.port = node.port();
        this.version = node.version();
        this.capabilities = node.capabilities();
        this.status = node.status();
        this.registeredAt = node.registeredAt();
        this.lastHeartbeatAt = node.lastHeartbeatAt();
        this.metrics = new NodeMetricsResponseVO(node.metrics());
    }
}
