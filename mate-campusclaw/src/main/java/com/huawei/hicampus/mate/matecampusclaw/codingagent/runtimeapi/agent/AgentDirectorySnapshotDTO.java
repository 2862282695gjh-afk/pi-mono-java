/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 按 Agent 目录解析出的当前只读运行配置。
 *
 * @param agentId Agent 标识
 * @param defaultModelId 默认模型标识
 * @param enabledModelIds 可切换模型白名单
 * @param agentDirectory Agent 只读工作目录
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record AgentDirectorySnapshotDTO(
        String agentId, String defaultModelId, List<String> enabledModelIds, Path agentDirectory) {
    public AgentDirectorySnapshotDTO {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(defaultModelId, "defaultModelId");
        Objects.requireNonNull(agentDirectory, "agentDirectory");
        enabledModelIds = List.copyOf(enabledModelIds);
    }
}
