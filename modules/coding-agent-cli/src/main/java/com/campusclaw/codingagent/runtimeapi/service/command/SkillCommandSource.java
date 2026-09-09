/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.command.definition.CommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.definition.DisplayCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.source.CommandDefinitionSource;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillCommandSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.campusclaw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从最新完整缓存发现直接绑定 Skill，不触发刷新，保留版本身份但不公开正文与路径。
 * 本类只提供内部发现能力，不承担 Skill 命令执行。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SkillCommandSource implements CommandDefinitionSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillCommandSource.class);

    private final AgentRuntimeManager agentRuntimeManager;

    public SkillCommandSource(AgentRuntimeManager agentRuntimeManager) {
        this.agentRuntimeManager = agentRuntimeManager;
    }

    @Override
    public CommandKind kind() {
        return CommandKind.SKILL;
    }

    @Override
    public List<ResolvedCommandDTO> list(RuntimeSessionDTO session) {
        PreparedAgentRuntime prepared = agentRuntimeManager.prepareCached(session.getAgentId());
        if (prepared == null) {
            return List.of();
        }
        List<ResolvedCommandDTO> commands = new ArrayList<>();
        for (SkillInfo skill : prepared.skills()) {
            resolved(session, prepared, skill).ifPresent(commands::add);
        }
        commands.sort(Comparator.comparing(ResolvedCommandDTO::name));
        return commands;
    }

    private Optional<ResolvedCommandDTO> resolved(
            RuntimeSessionDTO session, PreparedAgentRuntime prepared, SkillInfo skill) {
        String skillName = skill.name();
        if (!ClawConstants.Skill.isValidName(skillName) || !hasSkillMarkdown(prepared, skillName)) {
            LOGGER.warn("Ignoring invalid Runtime skill command: name={}", skillName);
            return Optional.empty();
        }
        return Optional.of(descriptor(session, prepared, skill));
    }

    @Override
    public List<? extends CommandDefinition> definitions(RuntimeSessionDTO session, PreparedAgentRuntime prepared) {
        // 完整快照已在 Manager 的 Agent 锁内验证文件身份，不能在发布新版本后再观察当前目录。
        return prepared.skills().stream()
                .map(skill -> descriptor(session, prepared, skill))
                .map(DisplayCommandDefinition::new)
                .toList();
    }

    private ResolvedCommandDTO descriptor(RuntimeSessionDTO session, PreparedAgentRuntime prepared, SkillInfo skill) {
        boolean idle = RuntimeSessionState.IDLE.matches(session.getState());
        String busyCode = RuntimeErrorCode.SESSION_BUSY.name();
        ResolvedCommandDTO.InputDTO input = new ResolvedCommandDTO.InputDTO(
                CommandInputMode.OPTIONAL.value(), idle, idle ? null : busyCode, true, "request", List.of());
        SkillCommandSnapshotDTO snapshot = new SkillCommandSnapshotDTO(
                prepared.agentId(), agentVersion(prepared), skill.id(), skill.version(), skill.content());
        return new ResolvedCommandDTO(
                ClawConstants.Skill.COMMAND_PREFIX + skill.name(),
                CommandKind.SKILL,
                skill.description(),
                idle,
                idle ? null : busyCode,
                input,
                snapshot);
    }

    private String agentVersion(PreparedAgentRuntime prepared) {
        return prepared.metadata() == null ? null : prepared.metadata().version();
    }

    private boolean hasSkillMarkdown(PreparedAgentRuntime prepared, String skillName) {
        Path skillFile = prepared.agentRoot()
                .resolve(ClawConstants.Runtime.DIRECTORY_NAME)
                .resolve(ClawConstants.Skill.DIRECTORY_NAME)
                .resolve(skillName)
                .resolve(ClawConstants.Skill.MARKDOWN_FILE_NAME);
        try {
            if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path realRoot = prepared.agentRoot().toFile().getCanonicalFile().toPath();
            Path expected = realRoot.resolve(ClawConstants.Runtime.DIRECTORY_NAME)
                    .resolve(ClawConstants.Skill.DIRECTORY_NAME)
                    .resolve(skillName)
                    .resolve(ClawConstants.Skill.MARKDOWN_FILE_NAME);
            Path canonicalFile = skillFile.toFile().getCanonicalFile().toPath();
            return canonicalFile.startsWith(realRoot) && canonicalFile.equals(expected);
        } catch (IOException error) {
            LOGGER.warn(
                    "Ignoring Runtime skill command with unreadable folder: name={}, cause={}",
                    skillName,
                    error.getMessage());
            return false;
        }
    }
}
