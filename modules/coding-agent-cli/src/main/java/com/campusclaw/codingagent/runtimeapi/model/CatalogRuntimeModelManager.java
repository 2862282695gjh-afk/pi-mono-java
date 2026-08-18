/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 以当前模型目录充当独立开发环境 Model Manager 的适配器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class CatalogRuntimeModelManager implements RuntimeModelManager {
    private final ModelCatalogService modelCatalogService;

    public CatalogRuntimeModelManager(ModelCatalogService modelCatalogService) {
        this.modelCatalogService = modelCatalogService;
    }

    @Override
    public Model resolveDefaultModel(AgentDirectorySnapshotDTO snapshot) {
        return resolveModel(snapshot, snapshot.defaultModelId());
    }

    @Override
    public Model resolveModel(AgentDirectorySnapshotDTO snapshot, String modelId) {
        try {
            if (!snapshot.enabledModelIds().contains(modelId)) {
                throw new RuntimeApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
            }
            return modelCatalogService.getAvailableModels().stream()
                    .filter(model -> model.id().equals(modelId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, RuntimeErrorCode.MANAGER_UNAVAILABLE, error);
        }
    }

    @Override
    public List<String> listAvailableModels(AgentDirectorySnapshotDTO snapshot) {
        try {
            Set<String> available = modelCatalogService.getAvailableModels().stream()
                    .map(Model::id)
                    .collect(Collectors.toSet());
            return snapshot.enabledModelIds().stream()
                    .filter(available::contains)
                    .toList();
        } catch (RuntimeException error) {
            throw new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, RuntimeErrorCode.MANAGER_UNAVAILABLE, error);
        }
    }
}
