/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.Agent;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance;
import com.campusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance.Status;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

/**
 * Runtime Event 接受边界、执行生命周期和流终止语义测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventServiceTest {
    private static final String SESSION_ID = "session_event_service";

    private static final String AGENT_ID = "agent_event_service";

    @Test
    void persistsRawFileIdsAndCompletesAcceptedStream() {
        Fixture fixture = new Fixture();
        UserEventRequestVO request = request("分析订单", List.of("file_a", "file_b"));
        MateCredentials credentials = MateCredentials.jwt("caller-1", "token-1");

        RuntimeEventStream stream = fixture.service.submit(SESSION_ID, request, Locale.US, credentials);
        fixture.agentFuture.complete(null);
        fixture.execution.completion().join();

        ArgumentCaptor<UserMessage> message = ArgumentCaptor.forClass(UserMessage.class);
        verify(fixture.agent).prompt(message.capture());
        String prompt = ((TextContent) message.getValue().content().getFirst()).text();
        assertThat(prompt).isEqualTo("分析订单\n\n[File IDs]\n- file_id: file_a\n- file_id: file_b");
        assertThat(fixture.acceptedEntry.getPayload()).contains("file_a", "file_b");
        assertThat(collect(stream))
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly("user.message", "session.status.idle", "stream.end");
        verify(fixture.registry).register(eq(SESSION_ID), any(), any(), eq(false), any(), any(), eq(credentials));
    }

    @Test
    void treatsSlashPrefixedTextAsOrdinaryUserMessage() {
        Fixture fixture = new Fixture();

        RuntimeEventStream stream = fixture.service.submit(
                SESSION_ID, request("/model model-b", List.of()), Locale.US, MateCredentials.empty());
        fixture.agentFuture.complete(null);
        fixture.execution.completion().join();

        ArgumentCaptor<UserMessage> message = ArgumentCaptor.forClass(UserMessage.class);
        verify(fixture.agent).prompt(message.capture());
        assertThat(((TextContent) message.getValue().content().getFirst()).text())
                .isEqualTo("/model model-b");
        assertThat(collect(stream))
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly("user.message", "session.status.idle", "stream.end");
    }

    @Test
    void rejectsDuplicateFileIdsBeforeReadingSession() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.submit(
                        SESSION_ID,
                        request(null, List.of("file_same", "file_same")),
                        Locale.US,
                        MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_EVENT_REQUEST));
        verify(fixture.repository, never()).find(anyString());
    }

    @Test
    void capacityFailureHappensBeforeUserEntryPersistence() {
        Fixture fixture = new Fixture();
        when(fixture.registry.register(anyString(), any(), any(), any(Boolean.class), any(), any(), any()))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED));

        assertThatThrownBy(() -> fixture.service.submit(
                        SESSION_ID, request("分析订单", List.of()), Locale.US, MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED));
        verify(fixture.repository, never()).acceptUserEvent(anyString(), any(), any());
    }

    @Test
    void unavailableRefreshedDefaultRejectsBeforeUserEntryPersistence() {
        Fixture fixture = new Fixture();
        when(fixture.modelManager.resolveAvailableModel(any(), eq("model_test")))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE));

        assertThatThrownBy(() -> fixture.service.submit(
                        SESSION_ID, request("分析订单", List.of()), Locale.US, MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE));
        verify(fixture.repository, never()).acceptUserEvent(anyString(), any(), any());
    }

    @Test
    void executionFailureUsesChineseSseMessage() {
        Fixture fixture = new Fixture();
        RuntimeEventStream stream = fixture.service.submit(
                SESSION_ID, request("分析订单", List.of()), Locale.SIMPLIFIED_CHINESE, MateCredentials.empty());

        fixture.agentFuture.completeExceptionally(new IllegalStateException("expected test failure"));

        assertThat(fixture.execution.completion()).isCompletedExceptionally();
        RuntimeSseEventVO terminal = collect(stream).getLast();
        assertThat(terminal.getEvent()).isEqualTo("stream.error");
        assertThat(terminal.getData())
                .containsEntry("resCode", RuntimeErrorCode.SESSION_EXECUTION_FAILED.name())
                .containsEntry("resMsg", "Session 执行失败。");
    }

    private static List<RuntimeSseEventVO> collect(RuntimeEventStream stream) {
        List<RuntimeSseEventVO> events = new ArrayList<>();
        stream.attach(Runnable::run, new RuntimeEventSubscriber() {
            @Override
            public void onEvent(RuntimeSseEventVO event) {
                events.add(event);
            }

            @Override
            public void onHeartbeat() {
                throw new AssertionError("completed stream must not emit heartbeat");
            }

            @Override
            public void onComplete() {
                // 同步 drain 已完成，无需额外协调。
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });
        return events;
    }

    private static UserEventRequestVO request(String message, List<String> fileIds) {
        UserEventRequestVO request = new UserEventRequestVO();
        request.readMessage(
                message == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.textNode(message));
        var files = JsonNodeFactory.instance.arrayNode();
        fileIds.forEach(files::add);
        request.readFileIds(files);
        return request;
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(SESSION_ID);
        session.setAgentId(AGENT_ID);
        session.setModelId("model_test");
        session.setState("idle");
        return session;
    }

    private static StaticMessageSource messages() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("SESSION_EXECUTION_FAILED", Locale.US, "Session execution failed.");
        messages.addMessage("SESSION_EXECUTION_FAILED", Locale.SIMPLIFIED_CHINESE, "Session 执行失败。");
        return messages;
    }

    private static final class Fixture {
        private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

        private final AgentDirectoryResolver resolver = mock(AgentDirectoryResolver.class);

        private final RuntimeModelManager modelManager = mock(RuntimeModelManager.class);

        private final RuntimeSessionEngineRegistry registry = mock(RuntimeSessionEngineRegistry.class);

        private final RuntimeExecutionTimeoutScheduler timeoutScheduler = mock(RuntimeExecutionTimeoutScheduler.class);

        private final Agent agent = mock(Agent.class);

        private final CompletableFuture<Void> agentFuture = new CompletableFuture<>();

        private final AtomicInteger ids = new AtomicInteger(100);

        private final RuntimeEventService service;

        private RuntimeActiveExecution execution;

        private RuntimeEntryDTO acceptedEntry;

        private Fixture() {
            RuntimeEventProperties eventProperties = new RuntimeEventProperties();
            RuntimeExecutionProperties executionProperties = new RuntimeExecutionProperties();
            RuntimeEventCursorCodec cursorCodec = mock(RuntimeEventCursorCodec.class);
            RuntimeEntryCodec codec = new RuntimeEntryCodec(new ObjectMapper());
            Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);
            RuntimeEntryIdGenerator idGenerator = () -> "entry_" + ids.getAndIncrement();
            RuntimeEventQueryService queryService = new RuntimeEventQueryService(repository, codec, cursorCodec);
            RuntimeEventProjectorFactory projectorFactory =
                    new RuntimeEventProjectorFactory(repository, codec, idGenerator, clock);
            RuntimeTerminalEventFactory terminalEventFactory = new RuntimeTerminalEventFactory(messages());
            RuntimeExecutionCoordinator coordinator = new RuntimeExecutionCoordinator(
                    registry,
                    repository,
                    projectorFactory,
                    timeoutScheduler,
                    executionProperties,
                    terminalEventFactory,
                    clock);
            RuntimeEventStreamFactory streamFactory = new RuntimeEventStreamFactory(eventProperties, codec);
            RuntimeExecutionContextFactory contextFactory =
                    new RuntimeExecutionContextFactory(queryService, registry, codec, streamFactory, clock);
            RuntimeSessionModelReconciler reconciler =
                    new RuntimeSessionModelReconciler(repository, resolver, modelManager, codec, idGenerator, clock);
            service = new RuntimeEventService(
                    repository, codec, idGenerator, registry, contextFactory, coordinator, reconciler, clock);
            prepareAcceptedExecution();
        }

        private void prepareAcceptedExecution() {
            RuntimeSessionDTO session = session();
            AgentDirectorySnapshotDTO snapshot = new AgentDirectorySnapshotDTO(
                    AGENT_ID,
                    "model_test",
                    List.of("model_test"),
                    Path.of("/tmp/agent"),
                    Path.of("/tmp/agent/.campusclaw"));
            Model model = mock(Model.class);
            when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
            when(repository.listCurrentBranchEntries(SESSION_ID, 0, 500)).thenReturn(List.of());
            when(resolver.resolve(AGENT_ID)).thenReturn(snapshot);
            when(modelManager.resolveAvailableModel(snapshot, "model_test")).thenReturn(model);
            when(agent.subscribe(any())).thenReturn(() -> {});
            when(agent.prompt(any(UserMessage.class))).thenReturn(agentFuture);
            when(timeoutScheduler.schedule(any(), any(Duration.class))).thenReturn(mock(ScheduledFuture.class));
            when(repository.acceptUserEvent(anyString(), any(), any())).thenAnswer(invocation -> {
                acceptedEntry = invocation.getArgument(1);
                acceptedEntry.setEntrySeq(1L);
                session.setState("running");
                return new UserEventAcceptance(Status.ACCEPTED, session);
            });
            when(registry.register(anyString(), any(), any(), any(Boolean.class), any(), any(), any()))
                    .thenAnswer(invocation -> registerHolder(snapshot, invocation.getArgument(5)));
        }

        private RuntimeSessionHolder registerHolder(
                AgentDirectorySnapshotDTO snapshot, RuntimeActiveExecution activeExecution) {
            execution = activeExecution;
            RuntimeSessionHolder holder = new RuntimeSessionHolder(SESSION_ID, snapshot, agent, false);
            assertThat(holder.begin(activeExecution)).isTrue();
            return holder;
        }
    }
}
