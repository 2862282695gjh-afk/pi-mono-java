/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import com.campusclaw.codingagent.runtime.AgentRuntimeException;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.AgentRuntimeProperties;
import com.campusclaw.codingagent.runtime.MateServiceClient;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.campusclaw.codingagent.runtimeapi.service.command.SkillCommandSource;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 用同一组名称样例验证共享加载、提示词、受管缓存与命令发现的拒绝边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillNameContractTest {
    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String SKILL_ID = "skill-11111111111111111111111111111111";

    @TempDir
    private Path temporaryDirectory;

    private MateServiceClient client;

    private AgentRuntimeManager manager;

    @BeforeEach
    void setUp() {
        client = mock(MateServiceClient.class);
        manager = newManager();
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void shouldRejectInvalidRawNameAndSkipItInScanAndPrompt(String folder, String declaration) throws IOException {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Path skills = managed.resolve("skills");
        Path invalidFile = writeSkill(skills, folder, markdown(declaration));
        writeSkill(skills, "valid-skill", markdown("name: valid-skill\n"));
        SkillLoader loader = new SkillLoader();

        assertThatThrownBy(() -> loader.loadFromFile(invalidFile, "managed")).isInstanceOf(SkillLoadException.class);
        assertThat(loader.loadFromDirectory(skills, "managed"))
                .extracting(Skill::name)
                .containsExactly("valid-skill");
        String prompt = new RuntimeAgentPromptLoader().load(managed);
        Path control = Files.createDirectory(temporaryDirectory.resolve("control"));
        writeSkill(control.resolve("skills"), "valid-skill", markdown("name: valid-skill\n"));
        String expected = new RuntimeAgentPromptLoader()
                .load(control)
                .replace(control.toRealPath().toString(), managed.toRealPath().toString());
        assertThat(prompt).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void shouldRejectInvalidPublicationAndPreservePreviousCache(String folder, String declaration) throws IOException {
        stubSkill(folder, markdown(declaration));

        assertThatThrownBy(() -> manager.prepare(AGENT_ID)).isInstanceOf(AgentRuntimeException.class);
        assertThat(manager.prepareCached(AGENT_ID)).isNull();
        Path published = temporaryDirectory.resolve("agents").resolve(AGENT_ID).resolve(".campusclaw");
        assertThat(published).doesNotExist();

        String previous = markdown("name: valid-skill\n");
        stubSkill("valid-skill", previous);
        manager.prepare(AGENT_ID);
        stubSkill(folder, markdown(declaration));

        assertThatThrownBy(() -> manager.refresh(AGENT_ID)).isInstanceOf(AgentRuntimeException.class);
        clearInvocations(client);
        AgentRuntimeManager restarted = newManager();
        assertThat(restarted.prepareCached(AGENT_ID).skills())
                .extracting(SkillInfo::name)
                .containsExactly("valid-skill");
        assertThat(Files.readString(published.resolve("skills/valid-skill/SKILL.md"), StandardCharsets.UTF_8))
                .isEqualTo(previous);
        assertThat(commands(restarted)).extracting(ResolvedCommandDTO::name).containsExactly("skill:valid-skill");
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void shouldRejectCorruptCacheBeforeDiscoveryAndRebuildOnPrepare(String folder, String declaration)
            throws IOException {
        // 元数据名称合法，保证失败来自真实 Loader 而非元数据名称前置拒绝。
        String cacheName = ClawConstants.Skill.isValidName(folder) ? folder : "cached-skill";
        String valid = markdown("name: '" + cacheName + "'\n");
        stubSkill(cacheName, valid);
        Path managed = manager.prepare(AGENT_ID).agentRoot().resolve(".campusclaw");
        Path skillFile = managed.resolve("skills").resolve(cacheName).resolve("SKILL.md");
        Files.writeString(skillFile, markdown(declaration), StandardCharsets.UTF_8);
        clearInvocations(client);

        assertThat(newManager().prepareCached(AGENT_ID)).isNull();
        assertThat(commands(manager)).isEmpty();
        verifyNoInteractions(client);
        assertThat(manager.prepare(AGENT_ID).skills())
                .extracting(SkillInfo::name)
                .containsExactly(cacheName);
        assertThat(Files.readString(skillFile, StandardCharsets.UTF_8)).isEqualTo(valid);
        assertThat(commands(manager)).extracting(ResolvedCommandDTO::name).containsExactly("skill:" + cacheName);
    }

    @ParameterizedTest
    @MethodSource("validNames")
    void shouldPreserveExactStringNamesThroughAllConsumers(String name) throws IOException {
        String content = markdown("name: '" + name + "'\n");
        stubSkill(name, content);
        Path managed = manager.prepare(AGENT_ID).agentRoot().resolve(".campusclaw");
        SkillLoader loader = new SkillLoader();

        assertThat(loader.loadFromFile(managed.resolve("skills").resolve(name).resolve("SKILL.md"), "managed")
                        .name())
                .isEqualTo(name);
        assertThat(loader.loadFromDirectory(managed.resolve("skills"), "managed"))
                .extracting(Skill::name)
                .containsExactly(name);
        assertThat(new RuntimeAgentPromptLoader().load(managed)).contains("<name>" + name + "</name>");
        clearInvocations(client);
        assertThat(commands(newManager())).extracting(ResolvedCommandDTO::name).containsExactly("skill:" + name);
        verifyNoInteractions(client);
    }

    private AgentRuntimeManager newManager() {
        return new AgentRuntimeManager(
                new AgentRuntimeProperties(
                        temporaryDirectory.resolve("agents"), Duration.ofSeconds(1L), Duration.ofSeconds(2L)),
                client,
                new ObjectMapper());
    }

    private static List<ResolvedCommandDTO> commands(AgentRuntimeManager runtimeManager) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setAgentId(AGENT_ID);
        session.setState("idle");
        return new SkillCommandSource(runtimeManager).list(session);
    }

    private void stubSkill(String name, String content) {
        when(client.getAgentRuntime(AGENT_ID))
                .thenReturn(new AgentRuntime(
                        List.of("model"),
                        List.of(new SkillReference(SKILL_ID, "1.0")),
                        List.of(),
                        List.of(),
                        List.of(),
                        "Agent",
                        true,
                        AGENT_ID,
                        "agent",
                        "System",
                        List.of(),
                        "1.0"));
        when(client.querySkillInfo(SKILL_ID))
                .thenReturn(new SkillInfo(
                        name,
                        SKILL_ID,
                        "1.0",
                        "Test skill",
                        null,
                        content,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private static Path writeSkill(Path root, String name, String content) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return Files.writeString(directory.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }

    private static String markdown(String declaration) {
        return "---\n" + declaration + "description: Test skill\n---\nBody.\n";
    }

    static Stream<String> validNames() {
        return Stream.of("a", "0", "pdf-tools", "a".repeat(64), "null", "true", "123");
    }

    static Stream<Arguments> invalidNames() {
        return Stream.of(
                Arguments.of("missing-name", ""),
                Arguments.of("null", "name: null\n"),
                Arguments.of("empty-value", "name:\n"),
                Arguments.of("123", "name: 123\n"),
                Arguments.of("true", "name: true\n"),
                Arguments.of("array", "name: [array]\n"),
                Arguments.of("object", "name: {key: value}\n"),
                Arguments.of("actual-folder", "name: different-name\n"),
                Arguments.of("empty-string", "name: ''\n"),
                Arguments.of("space", "name: ' '\n"),
                Arguments.of("trimmed", "name: ' trimmed '\n"),
                Arguments.of("line", "name: \"line\\n\"\n"),
                Arguments.of("pdf", "name: PDF\n"),
                Arguments.of("pdf-tools", "name: pdf_tools\n"),
                Arguments.of("-pdf", "name: '-pdf'\n"),
                Arguments.of("pdf-", "name: 'pdf-'\n"),
                Arguments.of("pdf--tools", "name: 'pdf--tools'\n"),
                Arguments.of("a".repeat(65), "name: '" + "a".repeat(65) + "'\n"));
    }
}
