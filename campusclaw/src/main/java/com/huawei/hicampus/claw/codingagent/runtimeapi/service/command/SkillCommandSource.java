/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.source.CommandDefinitionSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillCommandSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.huawei.hicampus.claw.codingagent.skill.SkillConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从最新完整缓存发现直接绑定 Skill，不触发刷新，保留版本身份但不公开正文与路径。
 * 本类只提供内部发现能力；Skill 命令执行由独立开发线负责。
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
        if (!SkillConstants.isValidName(skillName) || !hasSkillMarkdown(prepared, skillName)) {
            LOGGER.warn("Ignoring invalid Runtime skill command: name={}", skillName);
            return Optional.empty();
        }
        boolean idle = RuntimeSessionState.IDLE.matches(session.getState());
        String busyCode = RuntimeErrorCode.SESSION_BUSY.name();
        ResolvedCommandDTO.InputDTO input = new ResolvedCommandDTO.InputDTO(
                CommandInputMode.OPTIONAL.value(), idle, idle ? null : busyCode, true, "request", List.of());
        SkillCommandSnapshotDTO snapshot = new SkillCommandSnapshotDTO(
                prepared.agentId(), agentVersion(prepared), skill.id(), skill.version(), skill.content());
        return Optional.of(new ResolvedCommandDTO(
                SkillConstants.COMMAND_PREFIX + skillName,
                CommandKind.SKILL,
                skill.description(),
                idle,
                idle ? null : busyCode,
                input,
                snapshot));
    }

    private String agentVersion(PreparedAgentRuntime prepared) {
        return prepared.metadata() == null ? null : prepared.metadata().version();
    }

    private boolean hasSkillMarkdown(PreparedAgentRuntime prepared, String skillName) {
        Path skillFile = prepared.agentRoot()
                .resolve(AgentRuntimeManager.CAMPUSCLAW_DIRECTORY)
                .resolve(SkillConstants.DIRECTORY_NAME)
                .resolve(skillName)
                .resolve(SkillConstants.MARKDOWN_FILE_NAME);
        try {
            if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path realRoot = prepared.agentRoot().toFile().getCanonicalFile().toPath();
            Path expected = realRoot.resolve(AgentRuntimeManager.CAMPUSCLAW_DIRECTORY)
                    .resolve(SkillConstants.DIRECTORY_NAME)
                    .resolve(skillName)
                    .resolve(SkillConstants.MARKDOWN_FILE_NAME);
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
