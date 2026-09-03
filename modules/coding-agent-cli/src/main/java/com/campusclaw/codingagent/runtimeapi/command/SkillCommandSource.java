/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

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
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dynamic command source that exposes the direct-bound skills of the session agent
 * as {@code skill:<name>} commands. Definitions are resolved from the latest
 * complete prepared runtime without triggering an Agent refresh; skill content,
 * paths, IDs and versions never appear in descriptors.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
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
    public List<CommandDefinition> list(RuntimeSessionDTO session) {
        PreparedAgentRuntime prepared = preparedRuntime(session);
        if (prepared == null) {
            return List.of();
        }
        List<CommandDefinition> definitions = new ArrayList<>();
        for (SkillInfo skill : prepared.skills()) {
            descriptor(session, prepared, skill)
                    .ifPresent(descriptor -> definitions.add(new CommandDefinition(descriptor)));
        }
        definitions.sort(
                Comparator.comparing(definition -> definition.descriptor().getName()));
        return definitions;
    }

    private PreparedAgentRuntime preparedRuntime(RuntimeSessionDTO session) {
        return agentRuntimeManager.prepareCached(session.getAgentId());
    }

    private Optional<CommandDescriptorDTO> descriptor(
            RuntimeSessionDTO session, PreparedAgentRuntime prepared, SkillInfo skill) {
        String skillName = skill.name();
        if (!SkillNameValidator.isValid(skillName) || !hasSkillMarkdown(prepared, skillName)) {
            LOGGER.warn("Ignoring invalid Runtime skill command: name={}", skillName);
            return Optional.empty();
        }
        boolean idle = RuntimeSessionState.IDLE.matches(session.getState());
        String busyCode = RuntimeErrorCode.SESSION_BUSY.name();
        CommandDescriptorDTO descriptor = new CommandDescriptorDTO();
        descriptor.setName(COMMAND_PREFIX + skillName);
        descriptor.setKind(CommandKind.SKILL);
        descriptor.setDescription(skill.description());
        descriptor.setAvailable(idle);
        if (!idle) {
            descriptor.setUnavailableCode(busyCode);
        }
        descriptor.setInput(inputDescriptor(idle, busyCode));
        return Optional.of(descriptor);
    }

    private CommandInputDescriptorDTO inputDescriptor(boolean idle, String busyCode) {
        CommandInputDescriptorDTO input = new CommandInputDescriptorDTO();
        input.setMode(CommandInputMode.OPTIONAL);
        input.setAcceptsFiles(true);
        input.setPlaceholder("request");
        input.setAvailable(idle);
        if (!idle) {
            input.setUnavailableCode(busyCode);
        }
        return input;
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
