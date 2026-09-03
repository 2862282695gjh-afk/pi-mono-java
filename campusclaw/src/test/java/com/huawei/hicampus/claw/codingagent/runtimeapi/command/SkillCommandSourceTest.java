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
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Session-scoped Skill command discovery: strict name filtering, stable sorting and
 * per-state availability derived from the prepared runtime snapshot.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillCommandSourceTest {

    private static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

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
                .thenReturn(prepared(
                        new SkillInfo("beta", null, null, "Beta skill", null, null, null, null, null, null),
                        new SkillInfo("alpha", null, null, "Alpha skill", null, null, null, null, null, null)));

        List<CommandDefinition> definitions = source.list(session("idle"));

        assertThat(definitions).hasSize(2);
        assertThat(definitions.get(0).descriptor().getName()).isEqualTo("skill:alpha");
        assertThat(definitions.get(1).descriptor().getName()).isEqualTo("skill:beta");
        CommandDescriptorDTO descriptor = definitions.get(0).descriptor();
        assertThat(descriptor.getKind()).isEqualTo(CommandKind.SKILL);
        assertThat(descriptor.getDescription()).isEqualTo("Alpha skill");
        assertThat(descriptor.isAvailable()).isTrue();
        assertThat(descriptor.getUnavailableCode()).isNull();
        assertThat(descriptor.getInput().getMode()).isEqualTo(CommandInputMode.OPTIONAL);
        assertThat(descriptor.getInput().isAvailable()).isTrue();
        assertThat(descriptor.getInput().isAcceptsFiles()).isTrue();
        assertThat(descriptor.getInput().getPlaceholder()).isEqualTo("request");
    }

    @Test
    void filtersSkillsFailingStrictNameRules() throws IOException {
        skillMarkdown("good-name");
        when(agentRuntimeManager.prepareCached(AGENT_ID))
                .thenReturn(prepared(
                        new SkillInfo("good-name", null, null, "ok", null, null, null, null, null, null),
                        new SkillInfo("pdf--tools", null, null, "legacy", null, null, null, null, null, null),
                        new SkillInfo("-lead", null, null, "legacy", null, null, null, null, null, null)));

        List<CommandDefinition> definitions = source.list(session("idle"));

        assertThat(definitions)
                .extracting(definition -> definition.descriptor().getName())
                .containsExactly("skill:good-name");
    }

    @Test
    void runningSessionMarksSkillUnavailableWithBusyCode() throws IOException {
        skillMarkdown("alpha");
        when(agentRuntimeManager.prepareCached(AGENT_ID))
                .thenReturn(prepared(new SkillInfo("alpha", null, null, "Alpha", null, null, null, null, null, null)));

        List<CommandDefinition> definitions = source.list(session("running"));

        CommandDescriptorDTO descriptor = definitions.getFirst().descriptor();
        assertThat(descriptor.isAvailable()).isFalse();
        assertThat(descriptor.getUnavailableCode()).isEqualTo("SESSION_BUSY");
        assertThat(descriptor.getInput().isAvailable()).isFalse();
        assertThat(descriptor.getInput().getUnavailableCode()).isEqualTo("SESSION_BUSY");
    }

    @Test
    void missingPreparedRuntimeYieldsNoCommands() {
        when(agentRuntimeManager.prepareCached(AGENT_ID)).thenReturn(null);

        assertThat(source.list(session("idle"))).isEmpty();
    }

    @Test
    void skillsWithoutMaterializedMarkdownAreIgnored() {
        when(agentRuntimeManager.prepareCached(AGENT_ID))
                .thenReturn(
                        prepared(new SkillInfo("ghost", null, null, "no folder", null, null, null, null, null, null)));

        assertThat(source.list(session("idle"))).isEmpty();
    }

    private PreparedAgentRuntime prepared(SkillInfo... skills) {
        return new PreparedAgentRuntime(AGENT_ID, agentRoot, null, List.of(skills));
    }

    private RuntimeSessionDTO session(String state) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setAgentId(AGENT_ID);
        session.setState(state);
        return session;
    }

    private void skillMarkdown(String name) throws IOException {
        Path skillDir = agentRoot
                .resolve(com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager.CAMPUSCLAW_DIRECTORY)
                .resolve("skills")
                .resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: x\n---\n");
    }
}
