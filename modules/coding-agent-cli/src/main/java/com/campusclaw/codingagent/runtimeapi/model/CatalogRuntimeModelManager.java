/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import java.util.LinkedHashSet;
import java.util.List;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

/**
 * 以当前模型目录充当独立开发环境 Model Manager 的适配器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
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
            Model selected = modelCatalogService.getAvailableModels().stream()
                    .filter(model -> matchesConfiguredModel(model, modelId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
            boolean configured = snapshot.enabledModelIds().stream()
                    .anyMatch(candidate -> matchesConfiguredModel(selected, candidate));
            if (!configured) {
                throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
            }
            return selected;
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.MANAGER_UNAVAILABLE, error);
        }
    }

    @Override
    public List<String> listAvailableModels(AgentDirectorySnapshotDTO snapshot) {
        try {
            List<Model> available = modelCatalogService.getAvailableModels();
            var resolved = new LinkedHashSet<String>();
            for (String configured : snapshot.enabledModelIds()) {
                available.stream()
                        .filter(model -> matchesConfiguredModel(model, configured))
                        .findFirst()
                        .map(Model::id)
                        .ifPresent(resolved::add);
            }
            return List.copyOf(resolved);
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.MANAGER_UNAVAILABLE, error);
        }
    }

    private static boolean matchesConfiguredModel(Model model, String configured) {
        int slash = configured.indexOf('/');
        if (slash < 0) {
            return model.id().equals(configured);
        }
        String provider = configured.substring(0, slash);
        String modelId = configured.substring(slash + 1);
        return model.provider().value().equals(provider) && model.id().equals(modelId);
    }
}
