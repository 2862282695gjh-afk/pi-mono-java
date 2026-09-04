/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Session-scoped Skill command discovery: strict name filtering, stable sorting,
 * per-state availability and the versioned snapshot carried by each resolution.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillCommandSourceTest {

    private static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

    private static final String AGENT_VERSION = "1.0.0";

    @TempDir
    Path agentRoot;

    private AgentRuntimeManager agentRuntimeManager;

    private SkillCommandSource source;

    @BeforeEach
    void setUp() {
        agentRuntimeManager = mock(AgentRuntimeManager.class);
        source = new SkillCommandSource(agentRuntimeManager);
    }

    @Test
    void listsSkillCommandsSortedByName() throws IOException {
        skillMarkdown("beta");
        skillMarkdown("alpha");
        when(agentRuntimeManager.prepareCached(AGENT_ID))
                .thenReturn(prepared(skill("beta", "Beta skill"), skill("alpha", "Alpha skill")));

        List<ResolvedCommand> commands = source.list(session("idle"));

        assertThat(commands).hasSize(2);
        assertThat(commands.get(0).name()).isEqualTo("skill:alpha");
        assertThat(commands.get(1).name()).isEqualTo("skill:beta");
        ResolvedCommand command = commands.get(0);
        assertThat(command.kind()).isEqualTo(CommandKind.SKILL);
        assertThat(command.description()).isEqualTo("Alpha skill");
        assertThat(command.available()).isTrue();
        assertThat(command.unavailableCode()).isNull();
        assertThat(command.input().mode()).isEqualTo("optional");
        assertThat(command.input().available()).isTrue();
        assertThat(command.input().acceptsFiles()).isTrue();
        assertThat(command.input().placeholder()).isEqualTo("request");
    }

    @Test
    void resolutionCarriesVersionedSkillSnapshot() throws IOException {
        skillMarkdown("alpha");
        when(agentRuntimeManager.prepareCached(AGENT_ID)).thenReturn(prepared(skill("alpha", "Alpha skill")));

        ResolvedCommand command = source.list(session("idle")).getFirst();

        assertThat(command.snapshot()).isNotNull();
        assertThat(command.snapshot().agentId()).isEqualTo(AGENT_ID);
        assertThat(command.snapshot().agentVersion()).isEqualTo(AGENT_VERSION);
        assertThat(command.snapshot().skillId()).isEqualTo("skill-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(command.snapshot().skillVersion()).isEqualTo("2.0.0");
        assertThat(command.snapshot().markdown()).contains("name: alpha");
    }

    @Test
    void filtersSkillsFailingStrictNameRules() throws IOException {
        skillMarkdown("good-name");
        when(agentRuntimeManager.prepareCached(AGENT_ID))
                .thenReturn(
                        prepared(skill("good-name", "ok"), skill("pdf--tools", "legacy"), skill("-lead", "legacy")));

        List<ResolvedCommand> commands = source.list(session("idle"));

        assertThat(commands).extracting(ResolvedCommand::name).containsExactly("skill:good-name");
    }

    @Test
    void runningSessionMarksSkillUnavailableWithBusyCode() throws IOException {
        skillMarkdown("alpha");
        when(agentRuntimeManager.prepareCached(AGENT_ID)).thenReturn(prepared(skill("alpha", "Alpha skill")));

        ResolvedCommand command = source.list(session("running")).getFirst();

        assertThat(command.available()).isFalse();
        assertThat(command.unavailableCode()).isEqualTo("SESSION_BUSY");
        assertThat(command.input().available()).isFalse();
        assertThat(command.input().unavailableCode()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void missingPreparedRuntimeYieldsNoCommands() {
        when(agentRuntimeManager.prepareCached(AGENT_ID)).thenReturn(null);

        assertThat(source.list(session("idle"))).isEmpty();
    }

    @Test
    void skillsWithoutMaterializedMarkdownAreIgnored() {
        when(agentRuntimeManager.prepareCached(AGENT_ID)).thenReturn(prepared(skill("ghost", "no folder")));

        assertThat(source.list(session("idle"))).isEmpty();
    }

    private PreparedAgentRuntime prepared(SkillInfo... skills) {
        AgentRuntime metadata = new AgentRuntime(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent A",
                true,
                AGENT_ID,
                "agent-a",
                "prompt",
                List.of(),
                AGENT_VERSION);
        return new PreparedAgentRuntime(AGENT_ID, agentRoot, metadata, List.of(skills));
    }

    private RuntimeSessionDTO session(String state) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setAgentId(AGENT_ID);
        session.setState(state);
        return session;
    }

    private SkillInfo skill(String name, String description) {
        String content = "---\nname: " + name + "\ndescription: " + description + "\n---\nBody.\n";
        return new SkillInfo(
                name,
                "skill-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "2.0.0",
                description,
                null,
                content,
                null,
                null,
                null,
                null);
    }

    private void skillMarkdown(String name) throws IOException {
        Path skillDir = agentRoot
                .resolve(AgentRuntimeManager.CAMPUSCLAW_DIRECTORY)
                .resolve("skills")
                .resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: " + name + "\n---\n");
    }
}
