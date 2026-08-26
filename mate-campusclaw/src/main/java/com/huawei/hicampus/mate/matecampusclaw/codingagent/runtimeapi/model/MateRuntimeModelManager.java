/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Api;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeFailures;

/**
 * 使用 Agent 已绑定的 Mate 模型标识构造通用 Provider 模型描述。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateRuntimeModelManager implements RuntimeModelManager {
    private final MateModelManagerProperties properties;

    public MateRuntimeModelManager(MateModelManagerProperties properties) {
        this.properties = properties;
    }

    @Override
    public Model resolveDefaultModel(AgentDirectorySnapshotDTO snapshot) {
        return resolveModel(snapshot, snapshot.defaultModelId());
    }

    @Override
    public Model resolveModel(AgentDirectorySnapshotDTO snapshot, String modelId) {
        if (!snapshot.enabledModelIds().contains(modelId)) {
            throw RuntimeFailures.raise(
                    "runtime.model.validate", RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED, "modelId", modelId);
        }
        return new Model(
                modelId,
                modelId,
                Api.OPENAI_COMPLETIONS,
                Provider.MATE_MODEL_MANAGER,
                null,
                properties.isReasoning(),
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                properties.getContextWindow(),
                properties.getMaxOutputTokens(),
                null,
                null,
                null);
    }

    @Override
    public List<String> listAvailableModels(AgentDirectorySnapshotDTO snapshot) {
        return List.copyOf(snapshot.enabledModelIds());
    }
}
