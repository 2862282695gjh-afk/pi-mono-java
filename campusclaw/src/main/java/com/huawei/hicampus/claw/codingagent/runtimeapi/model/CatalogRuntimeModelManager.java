/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.model;

import java.util.LinkedHashSet;
import java.util.List;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以当前模型目录充当独立开发环境 Model Manager 的适配器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class CatalogRuntimeModelManager implements RuntimeModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogRuntimeModelManager.class);

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
            RuntimeErrorCode errorCode = RuntimeErrorCode.MANAGER_UNAVAILABLE;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.model.resolve")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("modelId", modelId)
                    .setCause(error)
                    .log("CampusClaw failure: operation={}, errorCode={}", "runtime.model.resolve", errorCode.name());
            throw new RuntimeApiException(errorCode);
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
            RuntimeErrorCode errorCode = RuntimeErrorCode.MANAGER_UNAVAILABLE;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.model.list")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("agentId", snapshot.agentId())
                    .setCause(error)
                    .log("CampusClaw failure: operation={}, errorCode={}", "runtime.model.list", errorCode.name());
            throw new RuntimeApiException(errorCode);
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
