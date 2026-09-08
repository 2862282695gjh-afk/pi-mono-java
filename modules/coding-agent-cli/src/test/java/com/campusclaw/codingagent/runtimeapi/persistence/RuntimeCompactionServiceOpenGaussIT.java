/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionCoordinator;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionExecution;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionService;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventProjectorFactory;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.service.command.SessionCompactionApplicationService;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.CompactCommandContributor;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.compaction.CompactionProperties;
import com.campusclaw.codingagent.session.compaction.SessionCompactor;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.campusclaw.codingagent.tool.cron.AgentScopedCronToolFactory;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.publisher.Sinks;

/**
 * 使用真实 Service、受管 Agent、压缩器、Coordinator、Projector 和 openGauss 验证接入；仅替换外部服务。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCompactionServiceOpenGaussIT {
    @TempDir
    Path temporary;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    private final RuntimeEntryCodec codec =
            new RuntimeEntryCodec(mapper, new RuntimeMessageSourceConfiguration().messageSource());

    private final CampusClawAiService ai = mock(CampusClawAiService.class);

    private final AgentDirectoryResolver directories = mock(AgentDirectoryResolver.class);

    private final RuntimeModelManager models = mock(RuntimeModelManager.class);

    private final AgentRuntimeManager runtimes = mock(AgentRuntimeManager.class);

    private final RuntimeEntryIdGenerator ids = mock(RuntimeEntryIdGenerator.class);

    private final Sinks.One<AssistantMessage> summary = Sinks.one();

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    private AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository repository;

    private JdbcTemplate jdbc;

    private RuntimeSessionEngineRegistry registry;

    private RuntimeExecutionTimeoutScheduler scheduler;

    private RuntimeCompactionService service;

    @BeforeEach
    void createIsolatedRuntime() {
        context = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
        repository = context.getBean(RuntimeSessionRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        session.setId("compact-runtime-it-" + UUID.randomUUID());
        session.setAgentId("agent");
        session.setModelId("model");
        session.setState("idle");
        session.setResourceVersion(1L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd(temporary.toString());
        repository.create(session);
        var properties = new RuntimeExecutionProperties();
        properties.setMaxActive(1);
        registry = new RuntimeSessionEngineRegistry(
                factory(), mock(SubagentExecutionService.class), mock(AgentScopedCronToolFactory.class), properties);
        scheduler = new RuntimeExecutionTimeoutScheduler();
        Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
        var coordinator = new RuntimeCompactionCoordinator(
                registry,
                repository,
                new RuntimeEventProjectorFactory(repository, codec, ids, clock),
                scheduler,
                properties,
                clock);
        service =
                new RuntimeCompactionService(repository, registry, directories, models, codec, coordinator, ids, clock);
        when(ids.nextId()).thenReturn("internal-usage", "compaction-entry", "usage-record");
        when(ai.completeSimple(any(), any(), any())).thenReturn(summary.asMono());
        when(directories.resolve("agent")).thenReturn(directory());
        when(models.resolveAvailableModel(directory(), "model")).thenReturn(model());
    }

    @AfterEach
    void closeRuntime() {
        if (registry != null) {
            registry.find(session.getId()).ifPresent(holder -> holder.abort());
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "configuration", "failed-assistant"})
    void shouldReturnNoChangeForEffectiveEmptyContextWithoutDatabaseOrRuntimeSideEffects(String kind) {
        if (kind.equals("configuration")) {
            repository.updateThinking(
                    session.getId(),
                    null,
                    true,
                    ignored -> {},
                    ignored -> codec.thinkingChangedEntry(session.getId(), "configuration", false, true, "user", now),
                    now);
        } else if (kind.equals("failed-assistant")) {
            jdbc.update("UPDATE t_sessions SET state='running' WHERE id=?", session.getId());
            repository.appendEntry(codec.assistantEntry(session.getId(), "failed", response(StopReason.ERROR), now));
            repository.finishExecution(session.getId(), now);
        }
        var before = persistentState();
        assertThat(service.compact(session.getId(), null, Locale.CHINA)
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new RuntimeCompactionResultDTO(false, null));
        assertThat(persistentState()).isEqualTo(before);
        assertThat(registry.find(session.getId())).isEmpty();
        verifyNoInteractions(directories, models, runtimes, ai, ids);
    }

    @Test
    void shouldPersistRealCompactionWithItsUsageAndRestoreAfterCallerCancellation() throws Exception {
        seedHistory();
        var caller = service.compact(session.getId(), null, Locale.CHINA).toCompletableFuture();
        var holder = registry.find(session.getId()).orElseThrow();
        var execution = (RuntimeCompactionExecution) holder.activeExecution().orElseThrow();
        assertThat(repository.find(session.getId()).orElseThrow().getState()).isEqualTo("running");
        assertThat(execution.runId()).isEqualTo("internal-usage");
        assertThat(caller.cancel(true)).isTrue();
        assertThat(execution.completion()).isNotDone();
        assertThat(summary.tryEmitValue(response(StopReason.STOP))).isEqualTo(Sinks.EmitResult.OK);
        assertThat(execution.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isEqualTo(new RuntimeCompactionResultDTO(true, 3L));
        var entries = repository.listCurrentBranchEntries(session.getId(), 0L, 500);
        assertThat(entries)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("user.message", "user.message", "session.compaction.completed");
        assertThat(mapper.readTree(entries.getLast().getPayload())
                        .path("firstKeptEntryId")
                        .asText())
                .isEqualTo("second");
        var usage = jdbc.queryForMap("SELECT * FROM t_session_records WHERE session_id=?", session.getId());
        assertThat(usage).containsEntry("run_id", "internal-usage").containsEntry("record_seq", 4L);
        assertThat(mapper.readTree(usage.get("payload").toString())
                        .path("cause")
                        .asText())
                .isEqualTo("compaction");
        assertThat(jdbc.queryForObject(
                        "SELECT total_tokens FROM t_session_stats WHERE session_id=?", Long.class, session.getId()))
                .isEqualTo(12L);
        assertReleased(7L);
        try (var restarted = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class)) {
            var restored = restarted
                    .getBean(RuntimeSessionRepository.class)
                    .listCurrentBranchEntries(session.getId(), 0L, 500);
            var messages = codec.toAgentMessages(restored, model());
            assertThat(messages).hasSize(2).allSatisfy(message -> assertThat(message)
                    .isInstanceOf(UserMessage.class));
            var restoredSummary = (UserMessage) messages.getFirst();
            assertThat(restoredSummary.content()).hasSize(1);
            var summaryContent = (TextContent) restoredSummary.content().getFirst();
            assertThat(summaryContent.text()).contains("压缩摘要");
        }
    }

    @Test
    void shouldReleaseAcceptedExecutionAfterModelFailureWithoutWritingCompactionOrUsage() {
        seedHistory();
        var result = service.compact(session.getId(), null, Locale.ROOT).toCompletableFuture();
        assertThat(summary.tryEmitError(new IllegalStateException("model unavailable")))
                .isEqualTo(Sinks.EmitResult.OK);
        assertThatThrownBy(result::join).hasRootCauseMessage("model unavailable");
        assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                .hasSize(2);
        assertThat(jdbc.queryForList("SELECT * FROM t_session_records WHERE session_id=?", session.getId()))
                .isEmpty();
        assertReleased(7L);
    }

    @Test
    void shouldRejectACompetingDatabaseExecutionAfterPreparationWithoutClearingItsState() {
        seedHistory();
        when(directories.resolve("agent")).thenAnswer(ignored -> {
            assertThat(repository
                            .acceptUserEvent(session.getId(), user("competitor"), now)
                            .status())
                    .isEqualTo(UserEventAcceptance.Status.ACCEPTED);
            return directory();
        });
        assertThatThrownBy(() -> service.compact(session.getId(), null, Locale.ROOT))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_BUSY));
        var current = repository.find(session.getId()).orElseThrow();
        assertThat(current.getState()).isEqualTo("running");
        assertThat(current.getActiveLeafId()).isEqualTo("competitor");
        assertThat(current.getResourceVersion()).isEqualTo(6L);
        assertThat(registry.find(session.getId())).isEmpty();
        verifyNoInteractions(ai);
    }

    private AgentSessionFactory factory() {
        var metadata =
                mapper.convertValue(Map.of("bindingModels", List.of("model"), "enabled", true), AgentRuntime.class);
        when(runtimes.prepare("agent")).thenReturn(new PreparedAgentRuntime("agent", temporary, metadata, List.of()));
        var prompt = mock(RuntimeAgentPromptLoader.class);
        when(prompt.load(temporary.resolve(".campusclaw"))).thenReturn("system prompt");
        var properties = new CompactionProperties();
        properties.setKeepRecentTokens(1);
        properties.setSummaryRetryEnabled(false);
        return new AgentSessionFactory(
                ai,
                runtimes,
                mock(ConfiguredToolAssembler.class),
                new StaticListableBeanFactory().getBeanProvider(MateToolsetFactory.class),
                prompt,
                new SessionCompactor(ai, properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "success", "interrupt"})
    void shouldExecuteTheBuiltinThroughItsRequestScopeAndRealRuntime(String outcome) throws Exception {
        if (!outcome.equals("empty")) {
            seedHistory();
        }
        var before = persistentState();
        var application = new SessionCompactionApplicationService(service);
        try (var invocation = application.openInvocation(MateCredentials.appKey("test-id", "test-key", "test-token"))) {
            var catalog = new ResolvedCommandCatalog(CommandSessionSnapshotDTO.from(session), List.of(), Map.of());
            var request = new CommandExecutionContext(Locale.CHINA, catalog, invocation);
            var result = new CompactCommandContributor(application)
                    .definition()
                    .handler()
                    .execute(request, "")
                    .toCompletableFuture();
            invocation.close();
            if (outcome.equals("empty")) {
                assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(false, null));
                assertThat(persistentState()).isEqualTo(before);
                assertThat(invocation.interrupt()).isFalse();
                verifyNoInteractions(ai, directories, models, ids);
                return;
            }
            assertThat(result).isNotDone();
            if (outcome.equals("interrupt")) {
                assertThat(invocation.interrupt()).isTrue();
                assertThatThrownBy(result::join).hasRootCauseMessage("COMMAND_EXECUTION_FAILED");
                assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                        .hasSize(2);
                assertThat(jdbc.queryForList("SELECT * FROM t_session_records WHERE session_id=?", session.getId()))
                        .isEmpty();
            } else {
                assertThat(summary.tryEmitValue(response(StopReason.STOP))).isEqualTo(Sinks.EmitResult.OK);
                assertThat(result.get(5L, TimeUnit.SECONDS)).isEqualTo(new RuntimeCompactionResultDTO(true, 3L));
                assertThat(repository
                                .listCurrentBranchEntries(session.getId(), 0L, 500)
                                .getLast()
                                .getType())
                        .isEqualTo("session.compaction.completed");
            }
            assertThat(invocation.interrupt()).isFalse();
            assertReleased(7L);
        }
    }

    private void seedHistory() {
        for (String id : List.of("first", "second")) {
            assertThat(repository
                            .acceptUserEvent(session.getId(), user(id), now)
                            .status())
                    .isEqualTo(UserEventAcceptance.Status.ACCEPTED);
            repository.finishExecution(session.getId(), now);
        }
    }

    private RuntimeEntryDTO user(String id) {
        return codec.userEntry(session.getId(), id, "历史消息 " + id, List.of(), now);
    }

    private List<Object> persistentState() {
        return List.of(
                repository.find(session.getId()).orElseThrow(),
                jdbc.queryForList(
                        "SELECT * FROM t_session_entries WHERE session_id=? ORDER BY entry_seq", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_records WHERE session_id=?", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_sequences WHERE session_id=?", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_stats WHERE session_id=?", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_materialized WHERE session_id=?", session.getId()));
    }

    private void assertReleased(long expectedVersion) {
        var current = repository.find(session.getId()).orElseThrow();
        assertThat(current.getState()).isEqualTo("idle");
        assertThat(current.getResourceVersion()).isEqualTo(expectedVersion);
        assertThat(current.getModelId()).isEqualTo("model");
        assertThat(current.isThinking()).isFalse();
        assertThat(registry.find(session.getId())).isEmpty();
        var replacement = registry.register(
                "replacement", directory(), model(), false, List.of(), new RuntimeCompactionExecution(), null);
        registry.complete(replacement, replacement.activeExecution().orElseThrow());
    }

    private AgentDirectorySnapshotDTO directory() {
        return new AgentDirectorySnapshotDTO(
                "agent", "model", List.of("model"), temporary, temporary.resolve(".campusclaw"));
    }

    private static Model model() {
        return new Model(
                "model",
                "Model",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                1000,
                100,
                null,
                null,
                null);
    }

    private AssistantMessage response(StopReason reason) {
        return new AssistantMessage(
                List.of(new TextContent("压缩摘要")),
                "anthropic-messages",
                "anthropic",
                "model",
                null,
                new Usage(8, 4, 0, 0, 12, Cost.empty()),
                reason,
                null,
                now.toInstant().toEpochMilli());
    }
}
