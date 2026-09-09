/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.stereotype.Service;

/**
 * 从同一完整缓存投影 Agent 公开介绍，不刷新、不推断能力、不读取命令清单。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class AgentHelpQueryService {
    private final AgentRuntimeManager agentRuntimeManager;

    public AgentHelpQueryService(AgentRuntimeManager agentRuntimeManager) {
        this.agentRuntimeManager = agentRuntimeManager;
    }

    public HelpCommandResultDTO query(CommandSessionSnapshotDTO session, String arguments) {
        if (arguments != null && !arguments.isBlank()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        PreparedAgentRuntime prepared = agentRuntimeManager.prepareCached(session.agentId());
        if (prepared == null || prepared.metadata() == null) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        var metadata = prepared.metadata();
        String displayName = metadata.displayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = metadata.name();
        }
        if (displayName == null || displayName.isBlank()) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        return new HelpCommandResultDTO(
                displayName.strip(), visibleText(metadata.description()), visibleText(metadata.userCases()));
    }

    private List<String> visibleText(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .toList();
    }
}
