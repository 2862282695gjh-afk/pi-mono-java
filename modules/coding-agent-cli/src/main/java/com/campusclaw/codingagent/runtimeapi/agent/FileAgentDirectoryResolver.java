/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.agent;

import com.campusclaw.codingagent.runtime.AgentRuntimeException;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过统一 AgentRuntimeManager 准备目录并生成 Runtime API 快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class FileAgentDirectoryResolver implements AgentDirectoryResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileAgentDirectoryResolver.class);

    private final AgentRuntimeManager runtimeManager;

    public FileAgentDirectoryResolver(AgentRuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    @Override
    public AgentDirectorySnapshotDTO resolve(String agentId) {
        try {
            return AgentDirectorySnapshots.from(runtimeManager.prepare(agentId));
        } catch (IllegalArgumentException error) {
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
        }
    }
}
