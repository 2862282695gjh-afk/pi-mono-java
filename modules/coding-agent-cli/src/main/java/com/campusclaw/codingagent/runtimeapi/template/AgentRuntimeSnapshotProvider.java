/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.template;

/**
 * 根据 Agent 标识解析当前已激活发布快照的端口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface AgentRuntimeSnapshotProvider {
    AgentRuntimeSnapshotDTO resolveCurrent(String agentId);
}
