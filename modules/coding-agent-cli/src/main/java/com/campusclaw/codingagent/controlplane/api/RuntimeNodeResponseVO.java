/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import java.util.Set;

import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.domain.RuntimeCapability;

import lombok.Getter;

/**
 * 管理界面的活动 Runtime 节点摘要响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class RuntimeNodeResponseVO {
    private final String nodeId;

    private final String host;

    private final int port;

    private final String version;

    private final Set<RuntimeCapability> capabilities;

    private final int activeAgents;

    public RuntimeNodeResponseVO(NodeInfo node) {
        this.nodeId = node.nodeId();
        this.host = node.host();
        this.port = node.port();
        this.version = node.version();
        this.capabilities = node.capabilities();
        this.activeAgents = node.metrics().activeAgents();
    }
}
