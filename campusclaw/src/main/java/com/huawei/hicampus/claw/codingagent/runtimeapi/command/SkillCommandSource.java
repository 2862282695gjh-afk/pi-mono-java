/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

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
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.huawei.hicampus.claw.codingagent.skill.SkillNamePatterns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dynamic command source that exposes the direct-bound skills of the session agent
 * as {@code skill:<name>} commands. Commands are resolved from the latest complete
 * prepared runtime without triggering an Agent refresh; each resolution carries a
 * versioned {@link SkillCommandSnapshot} so later admission and execution never
 * re-resolve by name. Skill content, paths and IDs never appear in descriptors.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SkillCommandSource implements CommandDefinitionSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillCommandSource.class);

    /**
     * Reserved namespace prefix of Skill commands.
     */
    public static final String COMMAND_PREFIX = "skill:";

    private static final String SKILL_MARKDOWN_FILE = "SKILL.md";

    private final AgentRuntimeManager agentRuntimeManager;

    public SkillCommandSource(AgentRuntimeManager agentRuntimeManager) {
        this.agentRuntimeManager = agentRuntimeManager;
    }

    @Override
    public List<ResolvedCommand> list(RuntimeSessionDTO session) {
        PreparedAgentRuntime prepared = preparedRuntime(session);
        if (prepared == null) {
            return List.of();
        }
        List<ResolvedCommand> commands = new ArrayList<>();
        for (SkillInfo skill : prepared.skills()) {
            resolved(session, prepared, skill).ifPresent(commands::add);
        }
        commands.sort(Comparator.comparing(ResolvedCommand::name));
        return commands;
    }

    private PreparedAgentRuntime preparedRuntime(RuntimeSessionDTO session) {
        return agentRuntimeManager.prepareCached(session.getAgentId());
    }

    private Optional<ResolvedCommand> resolved(
            RuntimeSessionDTO session, PreparedAgentRuntime prepared, SkillInfo skill) {
        String skillName = skill.name();
        if (!SkillNamePatterns.isStrictValid(skillName) || !hasSkillMarkdown(prepared, skillName)) {
            LOGGER.warn("Ignoring invalid Runtime skill command: name={}", skillName);
            return Optional.empty();
        }
        boolean idle = RuntimeSessionState.IDLE.matches(session.getState());
        String busyCode = RuntimeErrorCode.SESSION_BUSY.name();
        ResolvedCommand.Input input = new ResolvedCommand.Input(
                CommandInputMode.OPTIONAL.value(), idle, idle ? null : busyCode, true, "request", null);
        SkillCommandSnapshot snapshot = new SkillCommandSnapshot(
                prepared.agentId(), agentVersion(prepared), skill.id(), skill.version(), skill.content());
        return Optional.of(new ResolvedCommand(
                COMMAND_PREFIX + skillName,
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
                .resolve("skills")
                .resolve(skillName)
                .resolve(SKILL_MARKDOWN_FILE);
        try {
            if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path realRoot = prepared.agentRoot().toRealPath();
            return skillFile.toRealPath().startsWith(realRoot);
        } catch (IOException error) {
            LOGGER.warn(
                    "Ignoring Runtime skill command with unreadable folder: name={}, cause={}",
                    skillName,
                    error.getMessage());
            return false;
        }
    }
}
