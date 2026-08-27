/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.agent;

import java.util.LinkedHashSet;
import java.util.List;

import com.campusclaw.codingagent.runtime.AgentRuntimeException;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
<<<<<<< HEAD
=======

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
>>>>>>> upstream/main

/**
 * 通过统一 AgentRuntimeManager 准备目录并生成 Runtime API 快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class FileAgentDirectoryResolver implements AgentDirectoryResolver {

<<<<<<< HEAD
    private final AgentRuntimeManager runtimeManager;

=======
    private static final Logger LOGGER = LoggerFactory.getLogger(FileAgentDirectoryResolver.class);

    private final AgentRuntimeManager runtimeManager;

>>>>>>> upstream/main
    public FileAgentDirectoryResolver(AgentRuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    @Override
    public AgentDirectorySnapshotDTO resolve(String agentId) {
        try {
            return snapshot(runtimeManager.prepare(agentId));
        } catch (IllegalArgumentException error) {
<<<<<<< HEAD
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_FOUND, error);
        } catch (AgentRuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
=======
            RuntimeErrorCode errorCode = RuntimeErrorCode.AGENT_NOT_FOUND;
            LOGGER.atWarn()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.resolve")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("agentId", agentId)
                    .setCause(error)
                    .log("CampusClaw failure: operation={}, errorCode={}", "runtime.agent.resolve", errorCode.name());
            throw new RuntimeApiException(errorCode);
        } catch (AgentRuntimeException error) {
            RuntimeErrorCode errorCode = RuntimeErrorCode.AGENT_NOT_AVAILABLE;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.prepare")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("agentId", agentId)
                    .setCause(error)
                    .log("CampusClaw failure: operation={}, errorCode={}", "runtime.agent.prepare", errorCode.name());
            throw new RuntimeApiException(errorCode);
>>>>>>> upstream/main
        }
    }

    private static AgentDirectorySnapshotDTO snapshot(PreparedAgentRuntime runtime) {
        if (!Boolean.TRUE.equals(runtime.metadata().enabled())) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        String defaultModel = runtime.metadata()
                .defaultModel()
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
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
                throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
            }
        }
        if (!models.contains(defaultModel)) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return List.copyOf(models);
    }
}
