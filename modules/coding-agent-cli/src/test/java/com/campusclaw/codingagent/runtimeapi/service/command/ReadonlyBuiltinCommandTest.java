/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.HelpCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.StatusCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.AgentHelpQueryService;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证真实只读 Contributor 的分派、请求快照复用和无副作用准入。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class ReadonlyBuiltinCommandTest {
    private final AgentRuntimeManager manager = mock(AgentRuntimeManager.class);

    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void shouldRegisterThreeNoArgumentCommandsWithoutReadingAgentOrSkillSource(String state) {
        SkillCommandSource skills = mock(SkillCommandSource.class);
        when(skills.kind()).thenReturn(CommandKind.SKILL);
        ResolvedCommandCatalog catalog =
                new CompositeCommandRegistry(List.of(source(), skills)).resolve(session(state), CommandKind.BUILTIN);

        assertThat(catalog.list()).extracting(ResolvedCommandDTO::name).containsExactly("help", "skills", "status");
        assertThat(catalog.list()).allSatisfy(command -> {
            assertThat(command.available()).isTrue();
            assertThat(command.unavailableCode()).isNull();
            assertThat(command.kind()).isEqualTo(CommandKind.BUILTIN);
            assertThat(command.input())
                    .isEqualTo(new ResolvedCommandDTO.InputDTO(
                            "none", false, "COMMAND_ARGUMENTS_NOT_SUPPORTED", false, null, List.of()));
        });
        assertThat(catalog.find("help").orElseThrow().description())
                .isEqualTo("Describe the current Agent's purpose and use cases.");
        verify(skills).kind();
        verifyNoMoreInteractions(skills);
        verifyNoInteractions(manager);
    }

    @Test
    void shouldReturnNormalizedAgentGuideFromOneCachedSnapshotPerRequest() {
        PreparedAgentRuntime prepared = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        when(prepared.metadata()).thenReturn(metadata);
        when(metadata.displayName()).thenReturn("  Agent display  ", " \n");
        when(metadata.name()).thenReturn("  agent-name  ");
        when(metadata.description()).thenReturn(List.of("  Purpose\nline  ", "  "), null);
        when(metadata.userCases()).thenReturn(List.of("  Case one  ", ""), null);
        when(manager.prepareCached("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(prepared);

        assertThat(execute(session("idle"), "help", ""))
                .isEqualTo(new HelpCommandResultDTO("Agent display", List.of("Purpose\nline"), List.of("Case one")));
        assertThat(execute(session("running"), "help", " \n"))
                .isEqualTo(new HelpCommandResultDTO("agent-name", List.of(), List.of()));
        verify(manager, times(2)).prepareCached("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verifyNoMoreInteractions(manager);
    }

    @Test
    void shouldReportAgentUnavailableWithoutACompleteCache() {
        assertThatThrownBy(() -> execute(session("idle"), "help", null))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
                    assertThat(error.status().value()).isEqualTo(422);
                });
        verify(manager).prepareCached("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verifyNoMoreInteractions(manager);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldReturnPersistedStatusWithoutReadingAgent(String arguments) {
        for (String state : List.of("idle", "running")) {
            var current = session(state);
            when(repository.find("session")).thenReturn(Optional.of(current));
            assertThat(execute(current, "status", arguments))
                    .isEqualTo(new SessionCommandResultDTO(current, false, null));
        }
        verify(repository, times(2)).find("session");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(manager);
    }

    @Test
    void shouldReadCompleteStatusInsteadOfReconstructingFromCatalog() {
        RuntimeSessionDTO session = session("idle");
        ResolvedCommandCatalog first = new CompositeCommandRegistry(List.of(source())).resolve(session);
        session.setState("running");
        session.setModelId(null);
        session.setThinking(false);
        session.setDisplayName("latest name");
        session.setResourceVersion(9L);
        when(repository.find("session")).thenReturn(Optional.of(session));
        assertThat(new RuntimeSessionStatusService(repository)
                        .query(first.session().id(), ""))
                .isEqualTo(new SessionCommandResultDTO(session, false, null));
        assertThat(first.session().state()).isEqualTo("idle");
        verify(repository).find("session");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(manager);
    }

    @Test
    void shouldRejectStatusForSessionDeletedAfterCatalogResolution() {
        assertThatThrownBy(() -> execute(session("idle"), "status", ""))
                .isInstanceOf(RuntimeApiException.class)
                .hasMessage("SESSION_NOT_FOUND");
        verify(repository).find("session");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "/status", "skill:review", "missing"})
    void shouldRejectNonemptyArgumentsForAllReadonlyCommands(String arguments) {
        for (String name : List.of("help", "status", "skills")) {
            assertThatThrownBy(() -> execute(session("running"), name, arguments))
                    .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
                        assertThat(error.status().value()).isEqualTo(400);
                    });
        }
        verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n"})
    void shouldKeepStatusAndSkillsWhitespaceRulesUnchanged(String arguments) {
        for (String name : List.of("status", "skills")) {
            assertThatThrownBy(() -> execute(session("idle"), name, arguments))
                    .isInstanceOf(RuntimeApiException.class)
                    .hasMessage("INVALID_COMMAND_REQUEST");
        }
        verifyNoInteractions(manager);
    }

    private CommandResultDTO execute(RuntimeSessionDTO session, String name, String arguments) {
        ResolvedCommandCatalog catalog =
                new CompositeCommandRegistry(List.of(source())).resolve(session, CommandKind.BUILTIN);
        BuiltinCommandDefinition definition =
                (BuiltinCommandDefinition) catalog.findDefinition(name).orElseThrow();
        return definition
                .handler()
                .execute(new CommandExecutionContext(Locale.US, catalog), arguments)
                .toCompletableFuture()
                .join();
    }

    private BuiltinCommandSource source() {
        return new BuiltinCommandSource(List.of(
                new HelpCommandContributor(new AgentHelpQueryService(manager)),
                new StatusCommandContributor(new RuntimeSessionStatusService(repository)),
                new SkillsCommandContributor(new BoundSkillQueryService(manager))));
    }

    private RuntimeSessionDTO session(String state) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session");
        session.setAgentId("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        session.setState(state);
        session.setModelId("provider/model");
        session.setThinking(true);
        session.setResourceVersion(1L);
        return session;
    }
}
