/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.claw.ai.CampusClawAiService;
import com.huawei.hicampus.claw.ai.stream.AssistantMessageEventStream;
import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.Context;
import com.huawei.hicampus.claw.ai.types.InputModality;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ModelCost;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillCommandInputDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.CommittedEventProjection;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.CommittedEventQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventQueryService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventStreamFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventSubscriber;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeExecutionContextFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeV2EventEncoder;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeV2EventProjectorFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeV2ExecutionCoordinator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeV2MessageEventService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeTerminalRetryScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.skill.SkillCommandExecutionService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.compaction.CompactionProperties;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactor;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.huawei.hicampus.claw.codingagent.tool.cron.AgentScopedCronToolFactory;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolsetFactory;
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

/**
 * 使用真实数据库、公共 Session 工厂和普通消息执行链验证 Skill 输入、准入与恢复。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillCommandExecutionOpenGaussIT {
    private static final String FILE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String FILE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @TempDir
    Path temporary;

    private final ObjectMapper mapper = new ObjectMapper();

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-08T00:00:00Z");

    private final CampusClawAiService ai = mock(CampusClawAiService.class);

    private final AgentRuntimeManager runtimes = mock(AgentRuntimeManager.class);

    private final AssistantMessageEventStream response = new AssistantMessageEventStream();

    private final CompletableFuture<Context> modelInput = new CompletableFuture<>();

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    private AnnotationConfigApplicationContext database;

    private RuntimeSessionRepository repository;

    private RuntimeSessionEngineRegistry registry;

    private RuntimeExecutionTimeoutScheduler scheduler;

    private RuntimeTerminalRetryScheduler terminalRetries;

    private RuntimeEventQueryService queries;

    private CommittedEventQueryService eventQueries;

    private RuntimeEntryCodec codec;

    private SkillCommandExecutionService service;

    @BeforeEach
    void createRuntime() {
        database = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
        repository = database.getBean(RuntimeSessionRepository.class);
        session.setId("skill-command-it-" + UUID.randomUUID());
        session.setAgentId("agent");
        session.setModelId("model");
        session.setState("idle");
        session.setResourceVersion(1L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd(temporary.toString());
        repository.create(session);
        assembleRuntime();
        when(runtimes.prepare("agent")).thenReturn(runtime("原始完整说明", true));
        when(ai.streamSimple(any(), any(), any())).thenAnswer(call -> {
            modelInput.complete(call.getArgument(1));
            return response;
        });
    }

    @AfterEach
    void closeRuntime() throws Exception {
        if (registry != null) {
            var holder = registry.find(session.getId());
            if (holder.isPresent()) {
                var completion = holder.get().activeExecution().orElseThrow().completion();
                holder.get().abort();
                completion.handle((unused, failure) -> null).get(5, TimeUnit.SECONDS);
            }
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (terminalRetries != null) {
            terminalRetries.close();
        }
        if (database != null) {
            try {
                var jdbc = database.getBean(JdbcTemplate.class);
                for (String table : List.of(
                        "t_session_event_projection",
                        "t_session_tool_confirmations",
                        "t_session_execution_segment_events",
                        "t_session_execution_segments",
                        "t_session_executions",
                        "t_session_events",
                        "t_session_entries",
                        "t_session_records",
                        "t_session_sequences",
                        "t_session_stats",
                        "t_session_materialized")) {
                    jdbc.update("DELETE FROM " + table + " WHERE session_id=?", session.getId());
                }
                assertThat(jdbc.update("DELETE FROM t_sessions WHERE id=?", session.getId()))
                        .isEqualTo(1);
            } finally {
                database.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testPersistsAndRestoresActualSnapshotTextEvenAfterDetach(boolean detach) throws Exception {
        String arguments = detach ? null : "  附加说明\n";
        String expected = detach ? "本次实际说明" : "本次实际说明\n\n" + arguments;
        String publicInvocation = detach ? "/skill:pdf" : "/skill:pdf " + arguments;
        when(runtimes.prepare("agent")).thenReturn(runtime("本次实际说明", true));
        RuntimeEventStream stream = service.execute(
                session.getId(),
                input(arguments),
                Locale.CHINA,
                MateCredentials.jwt("caller", "private-jwt", "private-token"));
        var execution =
                registry.find(session.getId()).orElseThrow().activeExecution().orElseThrow();
        assertThat(text((UserMessage)
                        modelInput.get(5, TimeUnit.SECONDS).messages().getLast()))
                .isEqualTo(expected + "\n\n[File IDs]\n- file_id: " + FILE_B + "\n- file_id: " + FILE_A);
        if (detach) {
            stream.detach();
            assertThat(execution.completion()).isNotDone();
        }
        response.pushDone(StopReason.STOP, answer());
        execution.completion().get(5, TimeUnit.SECONDS);
        assertThat(registry.find(session.getId())).isEmpty();
        assertThat(repository.find(session.getId()).orElseThrow().getState()).isEqualTo("idle");
        var entries = repository.listCurrentBranchEntries(session.getId(), 0L, 500);
        assertThat(entries)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("user.message", "assistant.message.completed", "session.status.idle");
        assertThat(mapper.readTree(entries.getFirst().getPayload())
                        .path("message")
                        .asText())
                .isEqualTo(expected);
        var publicEvent =
                eventQueries.list(session.getId(), null, null).getEvents().getFirst();
        assertThat(mapper.valueToTree(publicEvent)
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo(publicInvocation);
        if (!detach) {
            var receipt = mapper.valueToTree(collect(stream).getFirst().getData());
            assertThat(receipt.path("content").get(0).path("text").asText()).isEqualTo(publicInvocation);
            assertThat(receipt.toString()).doesNotContain("本次实际说明");
        }
        assertRestored(expected);
        verify(runtimes, times(1)).prepare("agent");
        verify(runtimes, times(0)).prepareCached(any());
    }

    private void assertRestored(String expected) {
        try (var reopened = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class)) {
            var restored =
                    reopened.getBean(RuntimeSessionRepository.class).listCurrentBranchEntries(session.getId(), 0L, 500);
            assertThat(text((UserMessage)
                            codec.toAgentMessages(restored, model()).getFirst()))
                    .isEqualTo(expected + "\n\n[File IDs]\n- file_id: " + FILE_B + "\n- file_id: " + FILE_A);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "oversize", "busy"})
    void testRejectsBeforeUserEntryAndReleasesPreparation(String condition) {
        RuntimeErrorCode expected = RuntimeErrorCode.COMMAND_NOT_FOUND;
        var input = input(null);
        if (condition.equals("missing")) {
            input.setSkillName("unbound");
        } else if (condition.equals("disabled")) {
            when(runtimes.prepare("agent")).thenReturn(runtime("body", false));
            expected = RuntimeErrorCode.AGENT_NOT_AVAILABLE;
        } else if (condition.equals("oversize")) {
            when(runtimes.prepare("agent")).thenReturn(runtime("x".repeat(262145), true));
            expected = RuntimeErrorCode.INVALID_COMMAND_REQUEST;
        } else if (condition.equals("busy")) {
            database.getBean(JdbcTemplate.class)
                    .update("UPDATE t_sessions SET state='running' WHERE id=?", session.getId());
            expected = RuntimeErrorCode.SESSION_BUSY;
        }
        assertThat(assertThrows(
                                RuntimeApiException.class,
                                () -> service.execute(session.getId(), input, Locale.CHINA, null))
                        .errorCode())
                .isEqualTo(expected);
        assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                .isEmpty();
        assertThat(registry.find(session.getId())).isEmpty();
        assertThat(repository.find(session.getId()).orElseThrow().getResourceVersion())
                .isEqualTo(1L);
        verifyNoInteractions(ai);
        when(runtimes.prepare("agent")).thenReturn(runtime("body", true));
        assertCapacityReleased();
    }

    @Test
    void testCapacityRejectsBeforeAcceptance() {
        var holder = registry.register("occupied", directory(), model(), false, List.of(), execution(), null);
        try {
            assertThat(assertThrows(
                                    RuntimeApiException.class,
                                    () -> service.execute(session.getId(), input(null), Locale.CHINA, null))
                            .errorCode())
                    .isEqualTo(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED);
        } finally {
            registry.complete(holder, holder.activeExecution().orElseThrow());
        }
        assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                .isEmpty();
        verifyNoInteractions(ai);
    }

    private void assertCapacityReleased() {
        var replacement = registry.register("replacement", directory(), model(), false, List.of(), execution(), null);
        registry.complete(replacement, replacement.activeExecution().orElseThrow());
        assertThat(registry.find("replacement")).isEmpty();
    }

    @Test
    void testDatabaseCompetitorKeepsItsEntryAndRunningState() {
        when(runtimes.prepare("agent")).thenAnswer(call -> {
            var competitor = codec.userEntry(session.getId(), "competitor", "competing input", List.of(), now);
            assertThat(repository
                            .acceptUserEvent(session.getId(), competitor, now)
                            .status())
                    .isEqualTo(UserEventAcceptance.Status.ACCEPTED);
            return runtime("body", true);
        });
        assertThat(assertThrows(
                                RuntimeApiException.class,
                                () -> service.execute(session.getId(), input(null), Locale.CHINA, null))
                        .errorCode())
                .isEqualTo(RuntimeErrorCode.SESSION_BUSY);
        assertThat(registry.find(session.getId())).isEmpty();
        assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                .extracting(RuntimeEntryDTO::getId)
                .containsExactly("competitor");
        assertThat(repository.find(session.getId()).orElseThrow().getState()).isEqualTo("running");
        verifyNoInteractions(ai);
    }

    private void assembleRuntime() {
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        codec = new RuntimeEntryCodec(mapper, messages);
        queries = new RuntimeEventQueryService(repository, codec);
        var properties = new RuntimeExecutionProperties();
        properties.setMaxActive(1);
        var prompt = mock(RuntimeAgentPromptLoader.class);
        when(prompt.load(any())).thenReturn("system");
        var factory = new AgentSessionFactory(
                ai,
                runtimes,
                mock(ConfiguredToolAssembler.class),
                new StaticListableBeanFactory().getBeanProvider(MateToolsetFactory.class),
                prompt,
                new SessionCompactor(ai, new CompactionProperties()));
        registry = new RuntimeSessionEngineRegistry(
                factory, mock(SubagentExecutionService.class), mock(AgentScopedCronToolFactory.class), properties);
        scheduler = new RuntimeExecutionTimeoutScheduler();
        Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
        RuntimeEntryIdGenerator ids = () -> UUID.randomUUID().toString();
        service = new SkillCommandExecutionService(messageEventService(messages, clock, ids, properties));
    }

    private RuntimeV2MessageEventService messageEventService(
            org.springframework.context.MessageSource messages,
            Clock clock,
            RuntimeEntryIdGenerator ids,
            RuntimeExecutionProperties properties) {
        var projection = new CommittedEventProjection(mapper);
        var eventFactory = new RuntimeCommittedEventFactory(mapper, messages);
        var encoder = new RuntimeV2EventEncoder(projection, mapper);
        var eventProperties = new RuntimeEventProperties();
        var persistence = new RuntimeExecutionPersistenceService(
                repository, database.getBean(RuntimeExecutionControlRepository.class), ids);
        terminalRetries = new RuntimeTerminalRetryScheduler();
        var projectorFactory = new RuntimeV2EventProjectorFactory(
                repository, persistence, codec, ids, eventFactory, encoder, clock, eventProperties);
        var coordinator = new RuntimeV2ExecutionCoordinator(
                registry,
                persistence,
                projectorFactory,
                scheduler,
                terminalRetries,
                properties,
                codec,
                ids,
                eventFactory,
                encoder,
                clock);
        var directories = mock(AgentDirectoryResolver.class);
        var models = mock(RuntimeModelManager.class);
        when(directories.resolve("agent")).thenReturn(directory());
        when(models.resolveAvailableModel(directory(), "model")).thenReturn(model());
        var contextFactory = new RuntimeExecutionContextFactory(
                queries, registry, codec, new RuntimeEventStreamFactory(eventProperties, codec), clock);
        var reconciler =
                new RuntimeSessionModelReconciler(repository, directories, models, codec, eventFactory, ids, clock);
        var messageEvents = new RuntimeV2MessageEventService(
                repository,
                codec,
                ids,
                registry,
                contextFactory,
                coordinator,
                reconciler,
                persistence,
                eventFactory,
                encoder,
                clock);
        eventQueries = new CommittedEventQueryService(repository, projection);
        return messageEvents;
    }

    private PreparedAgentRuntime runtime(String content, boolean enabled) {
        var metadata = mapper.convertValue(
                Map.of("id", "agent", "bindingModels", List.of("model"), "enabled", enabled), AgentRuntime.class);
        var skill = new SkillInfo("pdf", "skill", "v2", "description", null, content, null, null, null, null);
        return new PreparedAgentRuntime("agent", temporary, metadata, List.of(skill));
    }

    private AgentDirectorySnapshotDTO directory() {
        return new AgentDirectorySnapshotDTO(
                "agent", "model", List.of("model"), temporary, temporary.resolve(".campusclaw"));
    }

    private static SkillCommandInputDTO input(String arguments) {
        var input = new SkillCommandInputDTO();
        input.setSkillName("pdf");
        input.setArguments(arguments);
        input.setFileIds(List.of(FILE_B, FILE_A));
        return input;
    }

    private static Model model() {
        return new Model(
                "model",
                "Model",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                false,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                100000,
                1000,
                null,
                null,
                null);
    }

    private AssistantMessage answer() {
        return new AssistantMessage(
                List.of(new TextContent("回答")),
                "anthropic-messages",
                "anthropic",
                "model",
                null,
                Usage.empty(),
                StopReason.STOP,
                null,
                now.toInstant().toEpochMilli());
    }

    private static String text(UserMessage message) {
        return ((TextContent) message.content().getFirst()).text();
    }

    private static RuntimeActiveExecution execution() {
        return new RuntimeActiveExecution(mock(RuntimeEventStream.class));
    }

    private static List<RuntimeSseEventVO> collect(RuntimeEventStream stream) {
        var collected = new ArrayList<RuntimeSseEventVO>();
        stream.attach(Runnable::run, new RuntimeEventSubscriber() {
            public void onEvent(RuntimeSseEventVO event) {
                collected.add(event);
            }

            public void onHeartbeat() {
                throw new AssertionError("completed stream emitted heartbeat");
            }

            public void onComplete() {
                // 已完成流通过同步 drain 返回完整事件列表。
            }

            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });
        return collected;
    }
}
