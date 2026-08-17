/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.template;

import java.nio.file.Path;
import java.util.List;

/**
 * 创建 Session 时固定的 Agent 发布快照数据。
 *
 * @param agentId Agent 标识
 * @param bundleRevision 发布修订号
 * @param defaultModelId 默认模型标识
 * @param enabledModelIds 可切换模型白名单
 * @param runtimeDirectory 已验证的运行模板目录
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record AgentRuntimeSnapshotDTO(
        String agentId,
        String bundleRevision,
        String defaultModelId,
        List<String> enabledModelIds,
        Path runtimeDirectory) {
    public AgentRuntimeSnapshotDTO {
        enabledModelIds = List.copyOf(enabledModelIds);
    }
}
