/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

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

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.stream.AssistantMessageEventStream;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Context;
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
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillCommandInputDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventCursorCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventProjectorFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventProperties;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventQueryService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStreamFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventSubscriber;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeExecutionContextFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeExecutionCoordinator;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeTerminalEventFactory;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.service.command.skill.SkillCommandExecutionService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
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

/**
 * 使用真实数据库、公共 Session 工厂和普通消息执行链验证 Skill 输入、准入与恢复。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillCommandExecutionOpenGaussIT {
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

    private RuntimeEventQueryService queries;

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
        if (database != null) {
            try {
                var jdbc = database.getBean(JdbcTemplate.class);
                for (String table : List.of(
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
                .isEqualTo(expected + "\n\n[File IDs]\n- file_id: file_b\n- file_id: file_a");
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
                .containsExactly("user.message", "assistant.message.completed");
        assertThat(mapper.readTree(entries.getFirst().getPayload())
                        .path("message")
                        .asText())
                .isEqualTo(expected);
        assertThat(queries.list(session.getId(), null, null, Locale.CHINA)
                        .getEvents()
                        .getFirst())
                .containsEntry("message", expected)
                .containsEntry("fileIds", List.of("file_b", "file_a"));
        if (!detach) {
            assertThat(collect(stream).getFirst().getData()).containsEntry("message", expected);
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
                    .isEqualTo(expected + "\n\n[File IDs]\n- file_id: file_b\n- file_id: file_a");
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
        queries = new RuntimeEventQueryService(repository, codec, mock(RuntimeEventCursorCodec.class));
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
        var coordinator = new RuntimeExecutionCoordinator(
                registry,
                repository,
                new RuntimeEventProjectorFactory(
                        repository, codec, new RuntimeCommittedEventFactory(mapper, messages), ids, clock),
                scheduler,
                properties,
                new RuntimeTerminalEventFactory(messages),
                clock);
        var directories = mock(AgentDirectoryResolver.class);
        var models = mock(RuntimeModelManager.class);
        when(directories.resolve("agent")).thenReturn(directory());
        when(models.resolveAvailableModel(directory(), "model")).thenReturn(model());
        var contextFactory = new RuntimeExecutionContextFactory(
                queries, registry, codec, new RuntimeEventStreamFactory(new RuntimeEventProperties(), codec), clock);
        service = new SkillCommandExecutionService(new RuntimeEventService(
                repository,
                codec,
                ids,
                registry,
                contextFactory,
                coordinator,
                reconciler(directories, models, ids, clock, messages),
                clock));
    }

    private RuntimeSessionModelReconciler reconciler(
            AgentDirectoryResolver directories,
            RuntimeModelManager models,
            RuntimeEntryIdGenerator ids,
            Clock clock,
            org.springframework.context.MessageSource messages) {
        return new RuntimeSessionModelReconciler(
                repository, directories, models, codec, new RuntimeCommittedEventFactory(mapper, messages), ids, clock);
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
        input.setFileIds(List.of("file_b", "file_a"));
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
