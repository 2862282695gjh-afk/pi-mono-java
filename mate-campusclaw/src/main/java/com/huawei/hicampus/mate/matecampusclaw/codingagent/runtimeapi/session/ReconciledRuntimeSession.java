/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * 下一次执行开始前完成模型校准后的不可变 Session 快照。
 *
 * @param session 已持久化的 Session 配置
 * @param agentSnapshot 本次执行固定使用的 Agent 目录快照
 * @param model 本次执行固定使用的模型
 * @param configurationEntries 本轮开始前新增的配置领域事件
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record ReconciledRuntimeSession(
        RuntimeSessionDTO session,
        AgentDirectorySnapshotDTO agentSnapshot,
        Model model,
        List<RuntimeEntryDTO> configurationEntries) {
    public ReconciledRuntimeSession {
        configurationEntries = List.copyOf(configurationEntries);
    }
}
