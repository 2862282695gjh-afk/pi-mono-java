/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import java.util.List;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeFailures;

/**
 * Runtime Session 使用的模型校验和解析端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeModelManager {
    Model resolveDefaultModel(AgentDirectorySnapshotDTO snapshot);

    Model resolveModel(AgentDirectorySnapshotDTO snapshot, String modelId);

    List<String> listAvailableModels(AgentDirectorySnapshotDTO snapshot);

    default Model resolveAvailableModel(AgentDirectorySnapshotDTO snapshot, String modelId) {
        if (!listAvailableModels(snapshot).contains(modelId)) {
            throw RuntimeFailures.raise(
                    "runtime.model.validate", RuntimeErrorCode.MODEL_NOT_AVAILABLE, "modelId", modelId);
        }
        try {
            return resolveModel(snapshot, modelId);
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
                throw error;
            }
            throw new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE);
        }
    }
}
