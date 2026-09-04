/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.BuiltinCommandMetadataDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.StatusCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.HelpCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.StatusCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.CommandHelpFormatter;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;

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

    private final CommandHelpFormatter help = new CommandHelpFormatter();

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void shouldRegisterThreeRealHandlersWithoutReadingAgentOrSkillSource(String state) {
        SkillCommandSource skills = mock(SkillCommandSource.class);
        when(skills.kind()).thenReturn(CommandKind.SKILL);
        ResolvedCommandCatalog catalog =
                new CompositeCommandRegistry(List.of(source(), skills)).resolve(session(state), CommandKind.BUILTIN);

        assertThat(catalog.list()).extracting(ResolvedCommandDTO::name).containsExactly("help", "skills", "status");
        assertThat(catalog.list()).allSatisfy(command -> {
            assertThat(command.available()).isTrue();
            assertThat(command.unavailableCode()).isNull();
            assertThat(command.kind()).isEqualTo(CommandKind.BUILTIN);
            assertThat(command.input().acceptsFiles()).isFalse();
            assertThat(command.input().suggestions()).isEmpty();
        });
        assertThat(catalog.find("help").orElseThrow().input().mode()).isEqualTo("optional");
        assertThat(catalog.find("help").orElseThrow().input().available()).isTrue();
        assertThat(catalog.list().subList(1, 3)).allSatisfy(command -> {
            assertThat(command.input().mode()).isEqualTo("none");
            assertThat(command.input().available()).isFalse();
            assertThat(command.input().unavailableCode()).isEqualTo("COMMAND_ARGUMENTS_NOT_SUPPORTED");
        });
        verify(skills).kind();
        org.mockito.Mockito.verifyNoMoreInteractions(skills);
        verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "\u2003"})
    void shouldReuseSevenDescriptorsInHelpWithoutResolvingAgain(String arguments) {
        ResolvedCommandCatalog catalog = catalog("running", "thinking", "compact", "model", "name");
        HelpCommandResultDTO result = (HelpCommandResultDTO) execute(catalog, "help", arguments);

        assertThat(result.commands())
                .extracting(ResolvedCommandDTO::name)
                .containsExactly("compact", "help", "model", "name", "skills", "status", "thinking");
        assertThat(result.commands()).allSatisfy(command -> assertThat(command)
                .isSameAs(catalog.find(command.name()).orElseThrow()));
        verifyNoInteractions(manager);
    }

    @Test
    void shouldFindExactHelpNameAfterStrippingWhitespace() {
        ResolvedCommandCatalog catalog = catalog("idle");
        HelpCommandResultDTO result = (HelpCommandResultDTO) execute(catalog, "help", " \tstatus\u2003");

        assertThat(result.commands()).containsExactly(catalog.find("status").orElseThrow());
        assertThat(result.commands().getFirst()).isSameAs(catalog.find("status").orElseThrow());
        verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "Status", "sta", "skill:review"})
    void shouldRejectHelpNamesWithoutAnExactBuiltinMatch(String arguments) {
        assertThatThrownBy(() -> execute(catalog("idle"), "help", arguments))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.COMMAND_NOT_FOUND);
                    assertThat(error.status().value()).isEqualTo(404);
                });
    }

    @Test
    void shouldRejectLeadingSlashInHelpArgument() {
        assertThatThrownBy(() -> execute(catalog("idle"), "help", " /status "))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
                    assertThat(error.status().value()).isEqualTo(400);
                });
    }

    @Test
    void shouldExcludeSkillsEvenWhenGivenAMixedCatalog() {
        ResolvedCommandCatalog builtins = catalog("idle");
        List<ResolvedCommandDTO> descriptors = new ArrayList<>(builtins.list());
        descriptors.add(new ResolvedCommandDTO("skill:review", CommandKind.SKILL, "review", true, null, null, null));
        ResolvedCommandCatalog mixed = new ResolvedCommandCatalog(builtins.session(), descriptors, java.util.Map.of());

        assertThat(help.query(mixed, "").commands()).containsExactlyElementsOf(builtins.list());
        assertThatThrownBy(() -> help.query(mixed, "skill:review"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.COMMAND_NOT_FOUND));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldReturnPersistedStatusWithoutReadingAgent(String arguments) {
        for (String state : List.of("idle", "running")) {
            ResolvedCommandCatalog catalog = catalog(state);
            assertThat(execute(catalog, "status", arguments))
                    .isEqualTo(new StatusCommandResultDTO(state, "provider/model", true));
        }
        verifyNoInteractions(manager);
    }

    @Test
    void shouldKeepStatusBoundToTheRequestSnapshot() {
        RuntimeSessionDTO session = session("idle");
        CompositeCommandRegistry registry = new CompositeCommandRegistry(List.of(source()));
        ResolvedCommandCatalog first = registry.resolve(session, CommandKind.BUILTIN);
        session.setState("running");
        session.setModelId(null);
        session.setThinking(false);

        assertThat(execute(first, "status", "")).isEqualTo(new StatusCommandResultDTO("idle", "provider/model", true));
        assertThat(execute(registry.resolve(session, CommandKind.BUILTIN), "status", ""))
                .isEqualTo(new StatusCommandResultDTO("running", null, false));
        verifyNoInteractions(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n", "detail"})
    void shouldRejectReadonlyArgumentsBeforeReadingAnyAgent(String arguments) {
        ResolvedCommandCatalog catalog = catalog("running");
        for (String name : List.of("status", "skills")) {
            assertThatThrownBy(() -> execute(catalog, name, arguments))
                    .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                            .isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST));
        }
        verifyNoInteractions(manager);
    }

    @Test
    void shouldCopyHelpResultAndRepresentEmptyCatalogAsEmptyList() {
        ResolvedCommandCatalog catalog = catalog("idle");
        List<ResolvedCommandDTO> commands = new ArrayList<>(catalog.list());
        HelpCommandResultDTO result = new HelpCommandResultDTO(commands);
        commands.clear();

        assertThat(result.commands()).containsExactlyElementsOf(catalog.list());
        assertThatThrownBy(() -> result.commands().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(help.query(new ResolvedCommandCatalog(catalog.session(), List.of(), java.util.Map.of()), "")
                        .commands())
                .isEmpty();
    }

    private CommandResultDTO execute(ResolvedCommandCatalog catalog, String name, String arguments) {
        BuiltinCommandDefinition definition =
                (BuiltinCommandDefinition) catalog.findDefinition(name).orElseThrow();
        return definition
                .handler()
                .execute(new CommandExecutionContext(Locale.US, catalog), arguments)
                .toCompletableFuture()
                .join();
    }

    private ResolvedCommandCatalog catalog(String state, String... fixtures) {
        return new CompositeCommandRegistry(List.of(source(fixtures))).resolve(session(state), CommandKind.BUILTIN);
    }

    private BuiltinCommandSource source(String... fixtures) {
        List<BuiltinCommandContributor> contributors = new ArrayList<>(List.of(
                new HelpCommandContributor(help),
                new StatusCommandContributor(new RuntimeSessionStatusService()),
                new SkillsCommandContributor(new BoundSkillQueryService(manager))));
        for (String name : fixtures) {
            contributors.add(() -> new BuiltinCommandDefinition(
                    new BuiltinCommandMetadataDTO(name, "fixture", CommandInputMode.NONE, null, List.of()),
                    (session, withArguments) -> null,
                    (context, arguments) -> CompletableFuture.completedFuture(new HelpCommandResultDTO(List.of()))));
        }
        return new BuiltinCommandSource(contributors);
    }

    private RuntimeSessionDTO session(String state) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setAgentId("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        session.setState(state);
        session.setModelId("provider/model");
        session.setThinking(true);
        session.setResourceVersion(1L);
        return session;
    }
}
