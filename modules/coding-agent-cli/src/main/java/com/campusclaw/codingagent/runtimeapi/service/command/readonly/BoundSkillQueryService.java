/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command.readonly;

import java.util.Comparator;

import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO.SkillDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.stereotype.Service;

/**
 * 每次执行只读取最新完整绑定快照；缺失时不刷新、不降级为空列表。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class BoundSkillQueryService {
    private final AgentRuntimeManager agentRuntimeManager;

    public BoundSkillQueryService(AgentRuntimeManager agentRuntimeManager) {
        this.agentRuntimeManager = agentRuntimeManager;
    }

    public SkillsCommandResultDTO query(CommandSessionSnapshotDTO session, String arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        PreparedAgentRuntime prepared = agentRuntimeManager.prepareCached(session.agentId());
        if (prepared == null) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        return new SkillsCommandResultDTO(prepared.skills().stream()
                .map(skill -> new SkillDTO(skill.name(), skill.description()))
                .sorted(Comparator.comparing(SkillDTO::name))
                .toList());
    }
}
