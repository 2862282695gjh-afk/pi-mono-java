/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import java.util.List;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

/**
 * Runtime Session 使用的模型校验和解析端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeModelManager {
    Model resolveDefaultModel(AgentDirectorySnapshotDTO snapshot);

    /**
     * 仅从本地配置及目录解析模型，可供 Session 锁内能力复核使用。
     * 不得刷新 Agent、调用远端服务或获取请求凭据。
     *
     * @param snapshot 锁外取得的 Agent 目录快照
     * @param modelId 当前模型标识
     * @return 本地模型描述
     */
    Model resolveModel(AgentDirectorySnapshotDTO snapshot, String modelId);

    List<String> listAvailableModels(AgentDirectorySnapshotDTO snapshot);

    default Model resolveAvailableModel(AgentDirectorySnapshotDTO snapshot, String modelId) {
        if (!listAvailableModels(snapshot).contains(modelId)) {
            throw new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE);
        }
        try {
            return resolveModel(snapshot, modelId);
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
                throw error;
            }
            throw new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE, error);
        }
    }
}
