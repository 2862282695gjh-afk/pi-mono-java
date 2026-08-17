/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;

import org.springframework.http.HttpStatus;

/**
 * Runtime Session 使用的模型校验和解析端口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface RuntimeModelManager {
    Model resolveDefaultModel(AgentRuntimeSnapshotDTO snapshot);

    Model resolveModel(AgentRuntimeSnapshotDTO snapshot, String modelId);

    List<String> listAvailableModels(AgentRuntimeSnapshotDTO snapshot);

    default List<String> listAvailableModels(AgentRuntimeSnapshotDTO snapshot, CallerAuthContext caller) {
        return listAvailableModels(snapshot);
    }

    default Model resolveAvailableModel(AgentRuntimeSnapshotDTO snapshot, CallerAuthContext caller, String modelId) {
        if (!listAvailableModels(snapshot, caller).contains(modelId)) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.MODEL_NOT_AVAILABLE);
        }
        try {
            return resolveModel(snapshot, modelId);
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
                throw error;
            }
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.MODEL_NOT_AVAILABLE, error);
        }
    }
}
