/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import java.util.List;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;

/**
 * Runtime Session 使用的模型校验和解析端口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface RuntimeModelManager {
    Model resolveDefaultModel(AgentRuntimeSnapshotDTO snapshot);

    List<String> listAvailableModels(AgentRuntimeSnapshotDTO snapshot);
}
