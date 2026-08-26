/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent;

import java.util.LinkedHashSet;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeFailures;

/**
 * 通过统一 AgentRuntimeManager 准备目录并生成 Runtime API 快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class FileAgentDirectoryResolver implements AgentDirectoryResolver {
    private final AgentRuntimeManager runtimeManager;

    public FileAgentDirectoryResolver(AgentRuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    @Override
    public AgentDirectorySnapshotDTO resolve(String agentId) {
        try {
            return snapshot(runtimeManager.prepare(agentId));
        } catch (IllegalArgumentException error) {
            throw RuntimeFailures.raise(
                    "runtime.agent.resolve", RuntimeErrorCode.AGENT_NOT_FOUND, error, "agentId", agentId);
        } catch (AgentRuntimeException error) {
            throw RuntimeFailures.raise(
                    "runtime.agent.prepare", RuntimeErrorCode.AGENT_NOT_AVAILABLE, error, "agentId", agentId);
        }
    }

    private static AgentDirectorySnapshotDTO snapshot(PreparedAgentRuntime runtime) {
        if (!Boolean.TRUE.equals(runtime.metadata().enabled())) {
            throw RuntimeFailures.raise(
                    "runtime.agent.validate", RuntimeErrorCode.AGENT_NOT_AVAILABLE, "agentId", runtime.agentId());
        }
        String defaultModel = runtime.metadata()
                .defaultModel()
                .orElseThrow(() -> RuntimeFailures.raise(
                        "runtime.agent.validate",
                        RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED,
                        "agentId",
                        runtime.agentId()));
        List<String> models = validModels(runtime.metadata().bindingModels(), defaultModel);
        return new AgentDirectorySnapshotDTO(
                runtime.agentId(),
                defaultModel,
                models,
                runtime.agentRoot(),
                runtime.agentRoot().resolve(".campusclaw"));
    }

    private static List<String> validModels(List<String> configured, String defaultModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (String model : configured) {
            if (model == null || model.isBlank() || !models.add(model)) {
                throw RuntimeFailures.raise("runtime.agent.validate", RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
            }
        }
        if (!models.contains(defaultModel)) {
            throw RuntimeFailures.raise(
                    "runtime.agent.validate",
                    RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED,
                    "defaultModelId",
                    defaultModel);
        }
        return List.copyOf(models);
    }
}
