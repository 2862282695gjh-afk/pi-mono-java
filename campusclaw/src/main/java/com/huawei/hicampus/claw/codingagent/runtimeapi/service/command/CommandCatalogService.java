/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeException;
import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.CommandListResponseVO;

import org.springframework.stereotype.Service;

/**
 * 先检查 Session 存在性，再以一次完整 Agent 缓存生成共享命令发现响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CommandCatalogService {
    private final RuntimeSessionRepository repository;

    private final AgentRuntimeManager agentRuntimeManager;

    private final CompositeCommandRegistry registry;

    private final CommandDiscoveryResponseAssembler responseAssembler;

    public CommandCatalogService(
            RuntimeSessionRepository repository,
            AgentRuntimeManager agentRuntimeManager,
            CompositeCommandRegistry registry,
            CommandDiscoveryResponseAssembler responseAssembler) {
        this.repository = repository;
        this.agentRuntimeManager = agentRuntimeManager;
        this.registry = registry;
        this.responseAssembler = responseAssembler;
    }

    public CommandListResponseVO list(String sessionId) {
        var session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
        var prepared = completeSnapshot(session.getAgentId());
        return responseAssembler.assemble(registry.resolveComplete(session, prepared));
    }

    private PreparedAgentRuntime completeSnapshot(String agentId) {
        PreparedAgentRuntime prepared;
        try {
            prepared = agentRuntimeManager.prepareCached(agentId);
        } catch (AgentRuntimeException | IllegalArgumentException error) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
        }
        if (prepared == null || prepared.metadata() == null) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        return prepared;
    }
}
