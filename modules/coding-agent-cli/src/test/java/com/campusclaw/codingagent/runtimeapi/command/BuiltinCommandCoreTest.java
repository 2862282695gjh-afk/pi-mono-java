/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证 Builtin 核心的来源隔离、启动查重、请求快照和分派身份。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class BuiltinCommandCoreTest {
    private final RuntimeSessionDTO session = session();

    @Test
    void shouldResolveSortedSevenWithoutResolvingSkillSource() {
        SkillCommandSource skills = mock(SkillCommandSource.class);
        when(skills.kind()).thenReturn(CommandKind.SKILL);
        BuiltinCommandSource builtins = source("thinking", "status", "skills", "name", "model", "help", "compact");
        CompositeCommandRegistry registry = new CompositeCommandRegistry(List.of(skills, builtins));

        ResolvedCommandCatalog catalog = registry.resolve(session, CommandKind.BUILTIN);

        assertThat(catalog.list())
                .extracting(ResolvedCommandDTO::name)
                .containsExactly("compact", "help", "model", "name", "skills", "status", "thinking");
        assertThat(catalog.list()).extracting(ResolvedCommandDTO::kind).containsOnly(CommandKind.BUILTIN);
        verify(skills, never()).list(session);
        verify(skills, never()).definitions(session);
        assertThat(catalog.list()).allSatisfy(command -> {
            assertThat(command.input().acceptsFiles()).isFalse();
            assertThat(command.input().suggestions()).isEmpty();
        });
    }

    @Test
    void shouldRejectDuplicateContributorNamesAtSpringStartup() {
        new ApplicationContextRunner()
                .withBean(BuiltinCommandSource.class)
                .withBean("first", BuiltinCommandContributor.class, () -> () -> definition("help"))
                .withBean("second", BuiltinCommandContributor.class, () -> () -> definition("help"))
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("Duplicate builtin command name: help"));
    }

    @Test
    void shouldStartCoreBeforeConcreteContributorsArrive() {
        new ApplicationContextRunner().withBean(BuiltinCommandSource.class).run(context -> assertThat(
                        context.getBean(BuiltinCommandSource.class).list(session))
                .isEmpty());
    }

    @Test
    void shouldPreserveDisplayOnlySkillDefinitionsWhenResolvingAll() {
        CommandDefinitionSource skills = displaySource(CommandKind.SKILL, skillDescriptor());
        CompositeCommandRegistry registry = new CompositeCommandRegistry(List.of(source("help"), skills));

        ResolvedCommandCatalog catalog = registry.resolve(session);

        assertThat(catalog.list()).extracting(ResolvedCommandDTO::name).containsExactly("help", "skill:review");
        assertThat(catalog.findDefinition("skill:review")).get().isInstanceOf(DisplayCommandDefinition.class);
        assertThat(catalog.find("skill:review")).contains(skillDescriptor());
        assertThat(registry.resolve(session, CommandKind.SKILL).list())
                .extracting(ResolvedCommandDTO::name)
                .containsExactly("skill:review");
    }

    @Test
    void shouldReuseDefinitionAndDescriptorWithoutRepeatingResolution() {
        AtomicInteger evaluations = new AtomicInteger();
        BuiltinCommandDefinition definition = new BuiltinCommandDefinition(
                metadata("help", CommandInputMode.NONE, List.of()),
                (snapshot, withArguments) -> {
                    evaluations.incrementAndGet();
                    return null;
                },
                (context, arguments) -> CompletableFuture.completedFuture(
                        new TestResultDTO(context.catalog().list().getFirst().name())));
        BuiltinCommandContributor contributor = mock(BuiltinCommandContributor.class);
        when(contributor.definition()).thenReturn(definition);
        BuiltinCommandSource source = new BuiltinCommandSource(List.of(contributor));
        ResolvedCommandCatalog catalog =
                new CompositeCommandRegistry(List.of(source)).resolve(session, CommandKind.BUILTIN);
        CommandExecutionContext context = new CommandExecutionContext(Locale.US, catalog);

        assertThat(catalog.find("help")).containsSame(catalog.list().getFirst());
        assertThat(catalog.findDefinition("help")).containsSame(definition);
        assertThat(definition
                        .handler()
                        .execute(context, "")
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new TestResultDTO("help"));
        assertThat(context.catalog()).isSameAs(catalog);
        assertThat(context.locale()).isEqualTo(Locale.US);
        assertThat(evaluations.get()).isEqualTo(1);
        verify(contributor).definition();
    }

    @Test
    void shouldCopySessionObservationAndResolveNewStateForNewRequests() {
        BuiltinCommandDefinition model = new BuiltinCommandDefinition(
                metadata("model", CommandInputMode.OPTIONAL, List.of("provider/model")),
                (snapshot, withArguments) ->
                        withArguments && snapshot.state().equals("running") ? "SESSION_BUSY" : null,
                (context, arguments) -> CompletableFuture.completedFuture(new TestResultDTO(arguments)));
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(new BuiltinCommandSource(List.of(() -> model))));
        ResolvedCommandCatalog idle = registry.resolve(session, CommandKind.BUILTIN);
        session.setState("running");
        session.setModelId("changed/model");
        session.setThinking(false);
        session.setResourceVersion(8L);
        ResolvedCommandCatalog running = registry.resolve(session, CommandKind.BUILTIN);

        assertThat(idle.session())
                .isEqualTo(new CommandSessionSnapshotDTO("session-id", "agent-id", "idle", "provider/model", true, 7L));
        assertThat(idle.list().getFirst().input().available()).isTrue();
        assertThat(running.session().resourceVersion()).isEqualTo(8L);
        assertThat(running.list().getFirst().available()).isTrue();
        assertThat(running.list().getFirst().input().available()).isFalse();
        assertThat(running.list().getFirst().input().unavailableCode()).isEqualTo("SESSION_BUSY");
        assertThat(model.admission().unavailableCode(running.session(), true)).isEqualTo("SESSION_BUSY");
    }

    @Test
    void shouldKeepMetadataAndCatalogCollectionsImmutable() {
        List<String> suggestions = new ArrayList<>(List.of("first"));
        BuiltinCommandDefinition definition = new BuiltinCommandDefinition(
                metadata("help", CommandInputMode.NONE, suggestions),
                (snapshot, withArguments) -> null,
                (context, arguments) -> CompletableFuture.completedFuture(new TestResultDTO(arguments)));
        suggestions.add("later");
        List<BuiltinCommandContributor> contributors = new ArrayList<>(List.of(() -> definition));
        BuiltinCommandSource source = new BuiltinCommandSource(contributors);
        contributors.clear();
        ResolvedCommandCatalog catalog = new CompositeCommandRegistry(List.of(source)).resolve(session);
        ResolvedCommandDTO descriptor = catalog.list().getFirst();

        assertThat(descriptor.input().suggestions()).containsExactly("first");
        assertThat(descriptor.input().available()).isFalse();
        assertThat(descriptor.input().placeholder()).isNull();
        assertThat(descriptor.input().unavailableCode()).isEqualTo("COMMAND_ARGUMENTS_NOT_SUPPORTED");
        assertThatThrownBy(() -> descriptor.input().suggestions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.list().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(catalog.find("/help")).isEmpty();
        assertThat(catalog.findDefinition("missing")).isEmpty();
        assertThat(catalog.findDefinition(null)).isEmpty();
    }

    @Test
    void shouldRejectKindMismatchAndDuplicatesAcrossSources() {
        CompositeCommandRegistry mismatched =
                new CompositeCommandRegistry(List.of(displaySource(CommandKind.BUILTIN, skillDescriptor())));
        IllegalStateException mismatch =
                assertThrows(IllegalStateException.class, () -> mismatched.resolve(session, CommandKind.BUILTIN));
        assertThat(mismatch).hasMessageContaining("differs from its source");
        CompositeCommandRegistry duplicated = new CompositeCommandRegistry(List.of(source("help"), source("help")));
        IllegalStateException duplicate = assertThrows(IllegalStateException.class, () -> duplicated.resolve(session));
        assertThat(duplicate).hasMessageContaining("Duplicate command name: help");
    }

    private BuiltinCommandSource source(String... names) {
        List<BuiltinCommandContributor> contributors = java.util.Arrays.stream(names)
                .<BuiltinCommandContributor>map(name -> () -> definition(name))
                .toList();
        return new BuiltinCommandSource(contributors);
    }

    private BuiltinCommandDefinition definition(String name) {
        return new BuiltinCommandDefinition(
                metadata(name, CommandInputMode.NONE, List.of()),
                (snapshot, withArguments) -> null,
                (context, arguments) -> CompletableFuture.completedFuture(new TestResultDTO(arguments)));
    }

    private BuiltinCommandMetadataDTO metadata(String name, CommandInputMode mode, List<String> suggestions) {
        return new BuiltinCommandMetadataDTO(name, "description", mode, null, suggestions);
    }

    private CommandDefinitionSource displaySource(CommandKind kind, ResolvedCommandDTO descriptor) {
        return new CommandDefinitionSource() {
            @Override
            public CommandKind kind() {
                return kind;
            }

            @Override
            public List<ResolvedCommandDTO> list(RuntimeSessionDTO snapshot) {
                return List.of(descriptor);
            }
        };
    }

    private ResolvedCommandDTO skillDescriptor() {
        return new ResolvedCommandDTO(
                "skill:review",
                CommandKind.SKILL,
                "review",
                true,
                null,
                new ResolvedCommandDTO.InputDTO("optional", true, null, true, "request", List.of()),
                null);
    }

    private RuntimeSessionDTO session() {
        RuntimeSessionDTO result = new RuntimeSessionDTO();
        result.setId("session-id");
        result.setAgentId("agent-id");
        result.setState("idle");
        result.setModelId("provider/model");
        result.setThinking(true);
        result.setResourceVersion(7L);
        return result;
    }

    private record TestResultDTO(String value) implements CommandResultDTO {}
}
