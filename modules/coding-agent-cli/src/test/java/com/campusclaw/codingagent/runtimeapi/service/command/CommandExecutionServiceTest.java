/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.definition.DisplayCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandHandler;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionCall;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionService;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.BuiltinCommandMetadataDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.CompactCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.HelpCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.ModelCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.NameCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.StatusCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.ThinkingCommandContributor;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.AgentHelpQueryService;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.campusclaw.codingagent.runtimeapi.session.SessionEtagFactory;
import com.campusclaw.codingagent.runtimeapi.vo.BuiltinCommandRequestVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class CommandExecutionServiceTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final RuntimeCompactionService runtime = mock(RuntimeCompactionService.class);

    private final SessionCompactionApplicationService compaction =
            spy(new SessionCompactionApplicationService(runtime));

    private final SkillCommandSource skills = mock(SkillCommandSource.class);

    private final RuntimeSessionResponseAssembler sessions =
            new RuntimeSessionResponseAssembler(new SessionEtagFactory());

    private final CommandResponseAssembler responses = new CommandResponseAssembler(sessions);

    private final RuntimeSessionDTO session = session(3L);

    private final MateCredentials credentials = MateCredentials.appKey("id", "private-key", "private-token");

    private CompositeCommandRegistry registry;

    @BeforeEach
    void prepare() {
        when(repository.find("session")).thenReturn(Optional.of(session));
        when(skills.kind()).thenReturn(CommandKind.SKILL);
    }

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldNormalizeWithoutMutatingRequestAndUseOneDefinitionSnapshot(String arguments) {
        AtomicReference<CommandExecutionContext> seen = new AtomicReference<>();
        var definition = definition((context, value) -> {
            seen.set(context);
            assertThat(value).isEmpty();
            return CompletableFuture.completedFuture(guide());
        });
        var input = request("probe", arguments);
        var result = application(definition)
                .executeBuiltin("session", input, Locale.CHINA, credentials)
                .toCompletableFuture()
                .join();

        assertThat(seen.get().catalog().findDefinition("probe")).containsSame(definition);
        assertThat(seen.get().locale()).isEqualTo(Locale.CHINA);
        assertThat(input.getArguments()).isEqualTo(arguments);
        assertThat(result.resource()).isInstanceOf(com.campusclaw.codingagent.runtimeapi.vo.AgentHelpResponseVO.class);
        assertThat(result.etag()).isNull();
        verify(registry).resolve(session, CommandKind.BUILTIN);
        verify(repository).find("session");
        verify(skills, never()).definitions(any());
        verify(skills, never()).list(any());
        verifyNoInteractions(runtime);
        assertThatThrownBy(() -> seen.get().runtimeInvocation().orElseThrow().invoke("session", Locale.US))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "missing-session", "unknown", "busy", "bad-admission"})
    void shouldRejectBeforeAcquiringInvocationOrRunningHandler(String reason) {
        CommandHandler handler = mock(CommandHandler.class);
        var definition = new BuiltinCommandDefinition(
                metadata(),
                (snapshot, input) -> reason.equals("busy")
                        ? "SESSION_BUSY"
                        : reason.equals("bad-admission") ? "UNKNOWN_POLICY_CODE" : null,
                handler);
        var service = application(definition);
        if (reason.equals("missing-session")) {
            when(repository.find("session")).thenReturn(Optional.empty());
        }
        var input = request(reason.equals("unknown") ? "unknown" : "probe", "");
        if (reason.equals("invalid")) {
            input = null;
        }
        var result =
                service.executeBuiltin("session", input, Locale.US, credentials).toCompletableFuture();
        String expected =
                switch (reason) {
                    case "invalid" -> "INVALID_COMMAND_REQUEST";
                    case "missing-session" -> "SESSION_NOT_FOUND";
                    case "unknown" -> "COMMAND_NOT_FOUND";
                    case "busy" -> "SESSION_BUSY";
                    default -> "COMMAND_EXECUTION_FAILED";
                };
        assertThatThrownBy(result::join)
                .hasCauseInstanceOf(RuntimeApiException.class)
                .hasRootCauseMessage(expected);
        verifyNoInteractions(handler, compaction, runtime);
        if (reason.equals("invalid") || reason.equals("missing-session")) {
            verify(registry, never()).resolve(any(), any(CommandKind.class));
        }
    }

    @Test
    void shouldProjectTheAuthoritativeResultInsteadOfTheOldCatalog() {
        var committed = session(8L);
        committed.setDisplayName("committed");
        var definition = definition((context, arguments) -> {
            assertThat(context.session().resourceVersion()).isEqualTo(3L);
            assertThat(arguments).isEqualTo("  preserve internal whitespace  ");
            return CompletableFuture.completedFuture(new SessionCommandResultDTO(committed, true, 88L));
        });
        var result = application(definition)
                .executeBuiltin("session", request("probe", "  preserve internal whitespace  "), Locale.US, credentials)
                .toCompletableFuture()
                .join();
        var expected = sessions.getView(committed);
        var json = JsonMapper.builder().findAndAddModules().build();
        assertThat(json.<JsonNode>valueToTree(result.resource())).isEqualTo(json.valueToTree(expected.resource()));
        assertThat(result.etag()).isEqualTo(expected.etag());
        verify(repository).find("session");
    }

    @Test
    void shouldNotPropagateCallerCancellationToAcceptedCompact() {
        var terminal = new CompletableFuture<RuntimeCompactionResultDTO>();
        var call = mock(RuntimeCompactionCall.class);
        when(call.result()).thenReturn(terminal.minimalCompletionStage());
        when(runtime.start("session", credentials, Locale.CHINA)).thenReturn(call);
        var service = application(new CompactCommandContributor(compaction).definition());
        var result = service.executeBuiltin("session", request("compact", null), Locale.CHINA, credentials);

        assertThat(result.toCompletableFuture().cancel(true)).isTrue();
        assertThat(terminal).isNotDone();
        terminal.complete(new RuntimeCompactionResultDTO(true, 25L));

        var view = result.toCompletableFuture().join();
        assertThat(JsonMapper.builder()
                        .build()
                        .<JsonNode>valueToTree(view.resource())
                        .toString())
                .isEqualTo("{\"compacted\":true}");
        assertThat(view.etag()).isNull();
        verify(runtime).start("session", credentials, Locale.CHINA);
        verify(call, never()).interrupt();
    }

    @Test
    void shouldAwaitCompletionOnVirtualRequestThread() throws Exception {
        var entered = new CountDownLatch(1);
        var terminal = new CompletableFuture<CommandResultDTO>();
        var service = application(definition((context, arguments) -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            entered.countDown();
            return terminal;
        }));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = executor.submit(
                    () -> service.executeBuiltinAndAwait("session", request("probe", ""), Locale.US, credentials));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(response.isDone()).isFalse();
                terminal.complete(guide());
                assertThat(response.get(5, TimeUnit.SECONDS).resource())
                        .isInstanceOf(com.campusclaw.codingagent.runtimeapi.vo.AgentHelpResponseVO.class);
            } finally {
                terminal.completeExceptionally(new IllegalStateException("测试结束"));
            }
        }
    }

    @Test
    void shouldUnwrapAwaitedFailuresToSafeRuntimeApiErrors() {
        var service = application(definition(
                (context, arguments) -> CompletableFuture.failedStage(new CompletionException(new RuntimeApiException(
                        RuntimeErrorCode.SESSION_BUSY, new IllegalStateException("private-token"))))));

        var error = assertThrows(
                RuntimeApiException.class,
                () -> service.executeBuiltinAndAwait("session", request("probe", ""), Locale.US, credentials));
        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_BUSY);
        assertThat(error.getCause()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeErrorCode.class,
            names = {
                "INVALID_COMMAND_REQUEST",
                "COMMAND_NOT_FOUND",
                "SESSION_NOT_FOUND",
                "SESSION_BUSY",
                "AGENT_NOT_AVAILABLE",
                "MODEL_NOT_AVAILABLE",
                "THINKING_NOT_SUPPORTED",
                "SESSION_NAME_UPDATE_FAILED",
                "COMMAND_EXECUTION_FAILED",
                "MANAGER_UNAVAILABLE",
                "RUNTIME_CAPACITY_EXCEEDED"
            })
    void shouldPreserveStableAsyncCodesWithoutSensitiveCauses(RuntimeErrorCode code) {
        var definition = definition((context, arguments) -> CompletableFuture.failedStage(
                new CompletionException(new RuntimeApiException(code, new IllegalStateException("private-token")))));
        var result = application(definition)
                .executeBuiltin("session", request("probe", ""), Locale.US, credentials)
                .toCompletableFuture();
        assertThatThrownBy(result::join)
                .hasRootCauseMessage(code.name())
                .satisfies(error -> assertThat(error.getCause().getCause()).isNull());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"sync", "async", "null-stage", "null-result", "unknown-result", "model-binding", "put-code"})
    void shouldTranslateUnexpectedFailuresAndCloseScope(String phase) {
        AtomicReference<CommandExecutionContext> seen = new AtomicReference<>();
        var definition = definition((context, arguments) -> {
            seen.set(context);
            return switch (phase) {
                case "sync" -> throw new IllegalStateException("private-token");
                case "async" -> CompletableFuture.failedStage(new IllegalStateException("private-token"));
                case "null-stage" -> null;
                case "null-result" -> CompletableFuture.completedFuture(null);
                case "unknown-result" -> CompletableFuture.completedFuture(new UnsupportedResultDTO());
                case "model-binding" -> throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
                default -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_MODEL_UPDATE_FAILED);
            };
        });
        var result = application(definition)
                .executeBuiltin("session", request("probe", ""), Locale.US, credentials)
                .toCompletableFuture();
        assertThatThrownBy(result::join)
                .hasRootCauseMessage(phase.equals("model-binding") ? "MODEL_NOT_AVAILABLE" : "COMMAND_EXECUTION_FAILED")
                .satisfies(error -> assertThat(error.getCause().getCause()).isNull());
        assertThatThrownBy(() -> seen.get().runtimeInvocation().orElseThrow().invoke("session", Locale.US))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectDisplayOnlyDefinitionWithoutInstallingAFakeHandler() {
        var definition = definition((context, arguments) -> CompletableFuture.completedFuture(guide()));
        var service = application(definition);
        var descriptor = definition.describe(CommandSessionSnapshotDTO.from(session));
        var catalog = new ResolvedCommandCatalog(
                CommandSessionSnapshotDTO.from(session),
                List.of(descriptor),
                Map.of("probe", new DisplayCommandDefinition(descriptor)));
        doReturn(catalog).when(registry).resolve(session, CommandKind.BUILTIN);
        var result = service.executeBuiltin("session", request("probe", ""), Locale.US, credentials)
                .toCompletableFuture();
        assertThatThrownBy(result::join).hasRootCauseMessage("COMMAND_EXECUTION_FAILED");
        verifyNoInteractions(compaction);
    }

    private CommandExecutionService application(BuiltinCommandDefinition definition) {
        return application(new BuiltinCommandSource(List.of(() -> definition)));
    }

    private CommandExecutionService application(BuiltinCommandSource builtins) {
        registry = spy(new CompositeCommandRegistry(List.of(builtins, skills)));
        return new CommandExecutionService(repository, registry, compaction, responses, VALIDATION.getValidator());
    }

    @ParameterizedTest
    @CsvSource({
        "help,displayName,false",
        "status,sessionId,true",
        "name,sessionId,true",
        "model,models,false",
        "thinking,sessionId,true",
        "compact,compacted,false",
        "skills,skills,false"
    })
    void shouldExecuteAllSevenRealContributorsThroughTheApplication(String name, String field, boolean hasEtag) {
        var result = application(concreteBuiltins())
                .executeBuiltin("session", request(name, ""), Locale.US, credentials)
                .toCompletableFuture()
                .join();
        var body = JsonMapper.builder().findAndAddModules().build().<JsonNode>valueToTree(result.resource());
        assertThat(body.has(field)).isTrue();
        assertThat(body.properties())
                .extracting(java.util.Map.Entry::getKey)
                .doesNotContain("command", "changed", "sourceEventSeq", "resource", "etag");
        assertThat(result.etag()).isEqualTo(hasEtag ? new SessionEtagFactory().create("session", 8L) : null);
        verify(registry).resolve(session, CommandKind.BUILTIN);
        verify(skills, never()).definitions(any());
    }

    private BuiltinCommandSource concreteBuiltins() {
        var committed = new SessionCommandResultDTO(session(8L), false, null);
        var help = mock(AgentHelpQueryService.class);
        var status = mock(RuntimeSessionStatusService.class);
        var name = mock(SessionNamingService.class);
        var model = mock(SessionModelConfigurationService.class);
        var thinking = mock(SessionThinkingConfigurationService.class);
        var bound = mock(BoundSkillQueryService.class);
        when(help.query(any(), anyString())).thenReturn(guide());
        when(status.query(anyString(), anyString())).thenReturn(committed);
        when(name.execute(anyString(), anyString())).thenReturn(committed);
        when(model.execute(anyString(), anyString())).thenReturn(new ModelCommandResultDTO("model", List.of()));
        when(thinking.execute(anyString(), anyString())).thenReturn(committed);
        when(bound.query(any(), anyString())).thenReturn(new SkillsCommandResultDTO(List.of()));
        var call = mock(RuntimeCompactionCall.class);
        when(call.result()).thenReturn(CompletableFuture.completedStage(new RuntimeCompactionResultDTO(false, null)));
        when(runtime.start("session", credentials, Locale.US)).thenReturn(call);
        return new BuiltinCommandSource(List.of(
                new HelpCommandContributor(help),
                new StatusCommandContributor(status),
                new NameCommandContributor(name),
                new ModelCommandContributor(model),
                new ThinkingCommandContributor(thinking),
                new CompactCommandContributor(compaction),
                new SkillsCommandContributor(bound)));
    }

    private BuiltinCommandDefinition definition(CommandHandler handler) {
        return new BuiltinCommandDefinition(metadata(), (snapshot, input) -> null, handler);
    }

    private BuiltinCommandMetadataDTO metadata() {
        return new BuiltinCommandMetadataDTO("probe", "Probe", CommandInputMode.OPTIONAL, null, List.of());
    }

    private static BuiltinCommandRequestVO request(String name, String arguments) {
        return BuiltinCommandRequestVO.builder().name(name).arguments(arguments).build();
    }

    private HelpCommandResultDTO guide() {
        return new HelpCommandResultDTO("Agent", List.of(), List.of());
    }

    @Test
    void shouldIsolateOverlappingRequests() {
        var pending = new HashMap<String, CompletableFuture<CommandResultDTO>>();
        var seen = new HashMap<String, CommandExecutionContext>();
        var definition = definition((context, arguments) -> {
            seen.put(arguments, context);
            var future = new CompletableFuture<CommandResultDTO>();
            pending.put(arguments, future);
            return future;
        });
        var service = application(definition);
        var first = service.executeBuiltin("session", request("probe", "first"), Locale.US, credentials);
        var second = service.executeBuiltin("session", request("probe", "second"), Locale.CHINA, credentials);
        assertThat(seen.get("first").catalog()).isNotSameAs(seen.get("second").catalog());
        assertThat(seen.get("first").locale()).isEqualTo(Locale.US);
        assertThat(seen.get("second").locale()).isEqualTo(Locale.CHINA);
        pending.get("second").complete(new SessionCommandResultDTO(session(8L), true, 50L));
        assertThat(second.toCompletableFuture().join().etag())
                .isEqualTo(new SessionEtagFactory().create("session", 8L));
        assertThat(first.toCompletableFuture()).isNotDone();
        pending.get("first").complete(guide());
        assertThat(first.toCompletableFuture().join().etag()).isNull();
        assertThat(seen.get("first").runtimeInvocation().orElseThrow())
                .isNotSameAs(seen.get("second").runtimeInvocation().orElseThrow());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Probe", "/probe", "skill:pdf"})
    void shouldValidateExplicitBuiltinBuilderInputBeforeLookup(String name) {
        var service = application(definition((context, arguments) -> CompletableFuture.completedFuture(guide())));
        var result = service.executeBuiltin("session", request(name, ""), Locale.US, credentials)
                .toCompletableFuture();
        assertThatThrownBy(result::join).hasRootCauseMessage("INVALID_COMMAND_REQUEST");
        verify(repository, never()).find(anyString());
        verifyNoInteractions(compaction, runtime);
    }

    private static RuntimeSessionDTO session(long version) {
        var value = new RuntimeSessionDTO();
        value.setId("session");
        value.setAgentId("agent");
        value.setState("idle");
        value.setModelId("model");
        value.setResourceVersion(version);
        value.setCreatedAt(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        value.setUpdatedAt(OffsetDateTime.parse("2026-09-07T00:00:00Z"));
        return value;
    }

    private record UnsupportedResultDTO() implements CommandResultDTO {}
}
