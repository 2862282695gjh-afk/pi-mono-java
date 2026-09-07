/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeProperties;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillReference;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.source.CommandDefinitionSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.CompactCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.HelpCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.ModelCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.NameCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.StatusCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.ThinkingCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.AgentHelpQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.CommandListResponseVO.DescriptorResponseVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class CommandCatalogServiceTest {
    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String SKILL_ID = "skill-11111111111111111111111111111111";

    @TempDir
    Path temp;

    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final MateServiceClient client = mock(MateServiceClient.class);

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    private AgentRuntimeManager manager;

    private BuiltinCommandSource builtins;

    private CommandCatalogService service;

    @BeforeEach
    void setUp() {
        var properties =
                new AgentRuntimeProperties(temp.resolve("agents"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        manager = spy(new AgentRuntimeManager(properties, client, new ObjectMapper()));
        session.setId("session");
        session.setAgentId(AGENT_ID);
        session.setState("idle");
        when(repository.find("session")).thenReturn(Optional.of(session));
        builtins = new BuiltinCommandSource(List.of(
                new HelpCommandContributor(mock(AgentHelpQueryService.class)),
                new StatusCommandContributor(mock(RuntimeSessionStatusService.class)),
                new NameCommandContributor(mock(SessionNamingService.class)),
                new ModelCommandContributor(mock(SessionModelConfigurationService.class)),
                new ThinkingCommandContributor(mock(SessionThinkingConfigurationService.class)),
                new CompactCommandContributor(mock(SessionCompactionApplicationService.class)),
                new SkillsCommandContributor(mock(BoundSkillQueryService.class))));
        service = service(List.of(builtins, new SkillCommandSource(manager)));
    }

    @Test
    void list_shouldCheckSessionBeforeAnyCacheRead_whenSessionMissing() {
        when(repository.find("session")).thenReturn(Optional.empty());
        var failure = assertThrows(RuntimeApiException.class, () -> service.list("session"));
        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_NOT_FOUND);
        verifyNoInteractions(manager, client);
        verify(repository).find("session");
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void list_shouldRejectMissingCompleteCache_inEveryState(String state) {
        session.setState(state);
        var failure = assertThrows(RuntimeApiException.class, () -> service.list("session"));
        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        verify(manager).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(manager);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void list_shouldAcceptCompleteEmptyBindings_withoutRefresh(String state) {
        publish("v1", List.of());
        session.setState(state);
        var response = service.list("session");
        assertThat(response.getCommands())
                .extracting(DescriptorResponseVO::getName)
                .containsExactlyElementsOf(
                        state.equals("idle")
                                ? List.of("help", "status", "name", "model", "thinking", "compact", "skills")
                                : List.of("help", "status", "name", "model", "thinking", "skills"));
        var name = response.getCommands().get(2);
        assertThat(name.getInput().getHint()).isEqualTo("[displayName]");
        if (state.equals("running")) {
            assertThat(response.getCommands().get(3).getInput()).isNull();
            assertThat(response.getCommands().get(4).getInput()).isNull();
        } else {
            assertThat(response.getCommands().get(3).getInput().getHint()).isEqualTo("[modelId]");
            assertThat(response.getCommands().get(4).getInput().getHint()).isEqualTo("[on|off]");
        }
        assertOnlyDiscoveryReads();
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void list_shouldUseCompleteBindings_includingWhenSkillsAreHidden(String state) {
        publish("v1", List.of("beta", "alpha"));
        session.setState(state);
        var response = service.list("session");
        assertThat(response.getCommands())
                .extracting(DescriptorResponseVO::getName)
                .containsExactlyElementsOf(
                        state.equals("idle")
                                ? List.of(
                                        "help",
                                        "status",
                                        "name",
                                        "model",
                                        "thinking",
                                        "compact",
                                        "skills",
                                        "skill:alpha",
                                        "skill:beta")
                                : List.of("help", "status", "name", "model", "thinking", "skills"));
        assertOnlyDiscoveryReads();
    }

    @Test
    void list_shouldKeepCapturedVersion_whenPublicationRemovesItsFiles() {
        publish("v1", List.of("alpha"));
        stubRuntime("v2", List.of("beta"));
        var switchPublication = new CommandDefinitionSource() {
            @Override
            public CommandKind kind() {
                return CommandKind.BUILTIN;
            }

            @Override
            public List<ResolvedCommandDTO> list(RuntimeSessionDTO observed) {
                manager.refresh(AGENT_ID);
                return List.of();
            }
        };
        var response = service(List.of(switchPublication, builtins, new SkillCommandSource(manager)))
                .list("session");
        assertThat(response.getCommands())
                .extracting(DescriptorResponseVO::getName)
                .contains("skill:alpha")
                .doesNotContain("skill:beta");
        assertThat(response.getCommands().getLast().getDescription()).isEqualTo("description v1");
        assertThat(Files.exists(agentRoot().resolve(".campusclaw/skills/alpha/SKILL.md")))
                .isFalse();
        assertThat(Files.isRegularFile(agentRoot().resolve(".campusclaw/skills/beta/SKILL.md")))
                .isTrue();
        verify(manager).prepareCached(AGENT_ID);
        verify(manager).refresh(AGENT_ID);
        verifyNoMoreInteractions(manager);
    }

    @Test
    void builtinResolution_shouldNotLoadSkillSource_orAgentCache() {
        var registry = new CompositeCommandRegistry(List.of(builtins, new SkillCommandSource(manager)));
        assertThat(registry.resolve(session, CommandKind.BUILTIN).list()).hasSize(7);
        verifyNoInteractions(manager, client);
    }

    @Test
    void help_shouldKeepExisting422_whenCatalogCacheIsMissing() {
        var failure = assertThrows(RuntimeApiException.class, () -> new AgentHelpQueryService(manager)
                .query(CommandSessionSnapshotDTO.from(session), ""));
        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-skill",
                "missing-system",
                "wrong-directory",
                "invalid-name",
                "file-link",
                "skill-link",
                "runtime-link",
                "root-alias",
                "sibling-alias",
                "outside-alias"
            })
    void list_shouldFailWholeCatalog_whenManagerRejectsUnsafeOrIncompleteCache(String damage) throws IOException {
        publish("v1", List.of("alpha"));
        damageCache(damage);
        var failure = assertThrows(RuntimeApiException.class, () -> service.list("session"));
        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        assertOnlyDiscoveryReads();
    }

    private void damageCache(String damage) throws IOException {
        Path managed = agentRoot().resolve(".campusclaw");
        Path skill = managed.resolve("skills/alpha");
        switch (damage) {
            case "missing-skill" -> Files.delete(skill.resolve("SKILL.md"));
            case "missing-system" -> Files.delete(managed.resolve("SYSTEM.md"));
            case "wrong-directory" -> Files.move(skill, managed.resolve("skills/different"));
            case "invalid-name" ->
                Files.writeString(
                        skill.resolve("SKILL.md"),
                        "---\nname: bad--name\ndescription: test\n---\nbody",
                        StandardCharsets.UTF_8);
            case "file-link" -> moveAndLink(skill.resolve("SKILL.md"), temp.resolve("skill-copy.md"));
            case "skill-link" -> moveAndLink(skill, temp.resolve("skill-copy"));
            case "runtime-link" -> moveAndLink(managed, temp.resolve("runtime-copy"));
            case "sibling-alias" ->
                moveAndLink(agentRoot(), agentRoot().resolveSibling("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
            case "outside-alias" -> moveAndLink(agentRoot(), temp.resolve("outside"));
            case "root-alias" -> {
                Files.move(agentRoot(), temp.resolve("saved-agent"));
                Files.createSymbolicLink(agentRoot(), agentRoot().getParent());
            }
            default -> throw new AssertionError(damage);
        }
    }

    private void moveAndLink(Path source, Path target) throws IOException {
        Files.move(source, target);
        Files.createSymbolicLink(source, target.toAbsolutePath());
    }

    private CommandCatalogService service(List<CommandDefinitionSource> sources) {
        return new CommandCatalogService(
                repository, manager, new CompositeCommandRegistry(sources), new CommandDiscoveryResponseAssembler());
    }

    private void assertOnlyDiscoveryReads() {
        verify(manager).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(manager);
        verify(repository).find("session");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(client);
    }

    private void publish(String version, List<String> names) {
        stubRuntime(version, names);
        manager.prepare(AGENT_ID);
        clearInvocations(manager, client);
    }

    private void stubRuntime(String version, List<String> names) {
        List<SkillReference> bindings = names.stream()
                .map(name -> new SkillReference(skillId(name), version))
                .toList();
        var metadata = new AgentRuntime(
                List.of(),
                bindings,
                List.of(),
                List.of(),
                List.of("purpose " + version),
                "Agent",
                true,
                AGENT_ID,
                "agent",
                "prompt " + version,
                List.of(),
                version);
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(metadata);
        for (String name : names) {
            var skill = new SkillInfo(
                    name,
                    skillId(name),
                    version,
                    "description " + version,
                    null,
                    "---\nname: " + name + "\ndescription: description " + version + "\n---\nbody " + version,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
            when(client.querySkillInfo(skill.id())).thenReturn(skill);
        }
    }

    private String skillId(String name) {
        return name.equals("alpha") ? SKILL_ID : "skill-22222222222222222222222222222222";
    }

    private Path agentRoot() {
        return temp.resolve("agents").resolve(AGENT_ID);
    }
}
