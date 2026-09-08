/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeExecutionContextDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.UserMessageAcceptanceDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ReconciledRuntimeSession;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.UserMessageContentRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.UserMessageContentRequestVO.FileRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.UserMessageContentRequestVO.TextRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.UserMessageEventRequestVO;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.cron.AgentScopedCronToolFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * v2 普通消息原子受理、公开内容隔离和首帧顺序测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeV2MessageEventServiceTest {
    @Test
    void shouldPreserveFileOrderAcrossPromptPrivateEntryAndPublicReceipt() {
        RealContextFixture fixture = new RealContextFixture(mockRegistry());
        List<String> fileIds = List.of("11111111111111111111111111111111", "22222222222222222222222222222222");

        RuntimeEventStream result = fixture.service.submit(
                "session-v2", requestWithFiles("分析订单", fileIds), Locale.US, MateCredentials.empty());

        result.complete();
        assertThat(promptText(fixture.startedMessage()))
                .isEqualTo("分析订单\n\n[File IDs]\n- file_id: " + fileIds.get(0) + "\n- file_id: " + fileIds.get(1));
        assertThat(fixture.acceptedEntry.getPayload())
                .isEqualTo(
                        "{\"message\":\"分析订单\",\"file_ids\":[\"" + fileIds.get(0) + "\",\"" + fileIds.get(1) + "\"]}");
        assertThat(fixture.acceptedEvent.getPayload())
                .isEqualTo("{\"content\":[{\"type\":\"text\",\"text\":\"分析订单\"},{\"type\":\"file\",\"fileId\":\""
                        + fileIds.get(0) + "\"},{\"type\":\"file\",\"fileId\":\"" + fileIds.get(1) + "\"}]}");
        assertThat(collect(result).getFirst().getData().get("content"))
                .isEqualTo(List.of(
                        java.util.Map.of("type", "text", "text", "分析订单"),
                        java.util.Map.of("type", "file", "fileId", fileIds.get(0)),
                        java.util.Map.of("type", "file", "fileId", fileIds.get(1))));
    }

    @Test
    void shouldTreatSlashPrefixedTextAsOrdinaryMessage() {
        RealContextFixture fixture = new RealContextFixture(mockRegistry());

        RuntimeEventStream result = fixture.service.submit(
                "session-v2", requestWithFiles("/model model-b", List.of()), Locale.US, MateCredentials.empty());

        result.complete();
        assertThat(promptText(fixture.startedMessage())).isEqualTo("/model model-b");
        assertThat(fixture.acceptedEntry.getPayload()).isEqualTo("{\"message\":\"/model model-b\",\"file_ids\":[]}");
        assertThat(fixture.acceptedEvent.getPayload())
                .isEqualTo("{\"content\":[{\"type\":\"text\",\"text\":\"/model model-b\"}]}");
    }

    @Test
    void shouldRejectFullExecutionRegistryBeforeAcceptingMessage() {
        RealContextFixture fixture = new RealContextFixture(fullRegistry());

        assertThatThrownBy(() -> fixture.service.submit(
                        "session-v2", requestWithFiles("分析订单", List.of()), Locale.US, MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED));
        verify(fixture.persistence, never()).acceptMessage(anyString(), any(), any(), any());
        verify(fixture.coordinator, never()).start(any(), any(), any(), any());
    }

    @Test
    void shouldRejectReconciliationFailureBeforeAcceptingMessage() {
        Fixture fixture = new Fixture(stream(16, 8192));
        when(fixture.reconciler.reconcile(any()))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE));

        assertThatThrownBy(() -> fixture.service.submit(
                        "session-v2", requestWithFiles("分析订单", List.of()), Locale.US, MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE));
        verify(fixture.persistence, never()).acceptMessage(anyString(), any(), any(), any());
        verify(fixture.contexts, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnAcceptedStreamWhenReceiptCannotBeEmitted() {
        Fixture fixture = new Fixture(stream(16, 8192));
        when(fixture.persistence.acceptMessage(eq("session-v2"), any(), any(), any()))
                .thenAnswer(invocation -> {
                    UserMessageAcceptanceDTO accepted =
                            fixture.captureAcceptance(invocation.getArgument(1), invocation.getArgument(2));
                    fixture.stream.complete();
                    return accepted;
                });

        RuntimeEventStream result = fixture.service.submit(
                "session-v2", requestWithFiles("分析订单", List.of()), Locale.US, MateCredentials.empty());

        assertThat(result).isSameAs(fixture.stream);
        assertThat(collect(result)).isEmpty();
        assertThat(fixture.execution.target()).isEqualTo(fixture.target);
        verify(fixture.persistence)
                .acceptMessage(eq("session-v2"), eq(fixture.acceptedEntry), eq(fixture.acceptedEvent), any());
        verify(fixture.coordinator)
                .handleAcceptedStartFailure(
                        eq(fixture.holder), eq(fixture.execution), any(IllegalStateException.class), eq(Locale.US));
        verify(fixture.coordinator, never()).start(any(), any(), any(), any());
    }

    @Test
    void shouldQueueCommittedReceiptBeforeStartingExecution() {
        Fixture fixture = new Fixture(stream(16, 8192));

        RuntimeEventStream result = fixture.service.submit(
                "session-v2", request("分析订单", "0123456789abcdef0123456789abcdef"), Locale.US, MateCredentials.empty());

        result.complete();
        assertThat(collect(result)).singleElement().satisfies(event -> {
            assertThat(event.isDataOnly()).isTrue();
            assertThat(event.getData()).containsEntry("type", "user.message").containsEntry("eventId", "event-user");
        });
        assertThat(fixture.execution.target()).isEqualTo(fixture.target);
        assertThat(fixture.execution.runId()).isEqualTo("execution-v2");
        verify(fixture.coordinator).start(fixture.holder, fixture.execution, fixture.context.userMessage(), Locale.US);
    }

    @Test
    void shouldKeepPreparedPublicInvocationSeparateFromExpandedPrompt() {
        Fixture fixture = new Fixture(stream(16, 8192));
        fixture.context = context(fixture.holder, fixture.execution, fixture.stream, "expanded private skill prompt");
        when(fixture.contexts.createPreparedMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(fixture.context);

        fixture.service.submitPreparedMessage(
                "session-v2",
                "/skill:pdf original arguments",
                (agentId, runtime) -> "expanded private skill prompt",
                List.of(),
                Locale.US,
                MateCredentials.empty());

        assertThat(fixture.acceptedEntry.getPayload()).contains("expanded private skill prompt");
        assertThat(fixture.acceptedEvent.getPayload())
                .contains("/skill:pdf original arguments")
                .doesNotContain("expanded private skill prompt");
    }

    @Test
    void shouldRejectReceiptCapacityBeforeAcceptingMessage() {
        Fixture fixture = new Fixture(stream(1, 1));

        assertThatThrownBy(() -> fixture.service.submit(
                        "session-v2", request("analysis", null), Locale.US, MateCredentials.empty()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE));
        verify(fixture.persistence, never()).acceptMessage(anyString(), any(), any(), any());
        verify(fixture.engines).complete(fixture.holder, fixture.execution);
        assertThat(fixture.execution.completion()).isCompleted();
    }

    private static UserMessageEventRequestVO request(String text, String fileId) {
        return requestWithFiles(text, fileId == null ? List.of() : List.of(fileId));
    }

    private static UserMessageEventRequestVO requestWithFiles(String text, List<String> fileIds) {
        List<UserMessageContentRequestVO> content = new ArrayList<>();
        TextRequestVO textBlock = new TextRequestVO();
        textBlock.setText(text);
        content.add(textBlock);
        for (String fileId : fileIds) {
            FileRequestVO file = new FileRequestVO();
            file.setFileId(fileId);
            content.add(file);
        }
        UserMessageEventRequestVO request = new UserMessageEventRequestVO();
        request.setContent(content);
        return request;
    }

    private static String promptText(UserMessage message) {
        return ((TextContent) message.content().getFirst()).text();
    }

    private static RuntimeSessionEngineRegistry mockRegistry() {
        RuntimeSessionEngineRegistry registry = mock(RuntimeSessionEngineRegistry.class);
        when(registry.withOperationLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return registry;
    }

    private static RuntimeSessionEngineRegistry fullRegistry() {
        AgentSessionFactory sessions = mock(AgentSessionFactory.class);
        Agent agent = mock(Agent.class);
        ManagedAgentSession managed = managedSession(agent);
        when(sessions.create(any())).thenReturn(managed);
        RuntimeExecutionProperties properties = new RuntimeExecutionProperties();
        properties.setMaxActive(1);
        RuntimeSessionEngineRegistry registry = new RuntimeSessionEngineRegistry(
                sessions, mock(SubagentExecutionService.class), mock(AgentScopedCronToolFactory.class), properties);
        registry.register(
                "occupied-session",
                snapshot("occupied-agent"),
                mock(Model.class),
                false,
                List.of(),
                new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly()),
                MateCredentials.empty());
        return registry;
    }

    private static ManagedAgentSession managedSession(Agent agent) {
        ManagedAgentSession session = mock(ManagedAgentSession.class);
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        when(metadata.bindingTools()).thenReturn(List.of());
        when(runtime.metadata()).thenReturn(metadata);
        when(runtime.skills()).thenReturn(List.of());
        when(session.agent()).thenReturn(agent);
        when(session.runtime()).thenReturn(runtime);
        return session;
    }

    private static AgentDirectorySnapshotDTO snapshot(String agentId) {
        return new AgentDirectorySnapshotDTO(
                agentId,
                "model-v2",
                List.of("model-v2"),
                Path.of("/" + agentId),
                Path.of("/" + agentId + "/.campusclaw"));
    }

    private static RuntimeExecutionContextDTO context(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventStream stream,
            String internalText) {
        return new RuntimeExecutionContextDTO(
                holder, execution, new UserMessage(internalText, 1L), internalText, stream);
    }

    private static RuntimeEventStream stream(int maxEvents, long maxBytes) {
        ObjectMapper mapper = new ObjectMapper();
        RuntimeEntryCodec codec =
                new RuntimeEntryCodec(mapper, new RuntimeMessageSourceConfiguration().messageSource());
        return new RuntimeEventStream(maxEvents, maxBytes, Duration.ofSeconds(1), codec::encodedSseBytes);
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
            public void onComplete() {}

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });
        return events;
    }

    private static final class Fixture {
        private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        private final RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        private final RuntimeExecutionContextFactory contexts = mock(RuntimeExecutionContextFactory.class);
        private final RuntimeV2ExecutionCoordinator coordinator = mock(RuntimeV2ExecutionCoordinator.class);
        private final RuntimeSessionModelReconciler reconciler = mock(RuntimeSessionModelReconciler.class);
        private final RuntimeExecutionPersistenceService persistence = mock(RuntimeExecutionPersistenceService.class);
        private final RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        private final RuntimeEventStream stream;
        private final RuntimeActiveExecution execution;
        private final ExecutionTargetDTO target =
                new ExecutionTargetDTO("session-v2", "execution-v2", "event-user", "segment-v2");
        private final RuntimeV2MessageEventService service;
        private RuntimeExecutionContextDTO context;
        private RuntimeEntryDTO acceptedEntry;
        private CommittedEventDTO acceptedEvent;

        private Fixture(RuntimeEventStream stream) {
            this.stream = stream;
            execution = new RuntimeActiveExecution(stream);
            context = context(holder, execution, stream, "analysis");
            RuntimeSessionDTO session = session();
            ObjectMapper mapper = new ObjectMapper();
            RuntimeEntryCodec codec =
                    new RuntimeEntryCodec(mapper, new RuntimeMessageSourceConfiguration().messageSource());
            RuntimeCommittedEventFactory events =
                    new RuntimeCommittedEventFactory(mapper, new RuntimeMessageSourceConfiguration().messageSource());
            AtomicInteger ids = new AtomicInteger();
            service = new RuntimeV2MessageEventService(
                    repository,
                    codec,
                    () -> ids.getAndIncrement() == 0 ? "event-user" : "event-" + ids.get(),
                    engines,
                    contexts,
                    coordinator,
                    reconciler,
                    persistence,
                    events,
                    new RuntimeV2EventEncoder(new CommittedEventProjection(mapper), mapper),
                    Clock.fixed(Instant.parse("2026-09-08T01:02:03.456Z"), ZoneOffset.UTC));
            configure(session);
        }

        private void configure(RuntimeSessionDTO session) {
            when(repository.find("session-v2")).thenReturn(java.util.Optional.of(session));
            when(reconciler.reconcile(session))
                    .thenReturn(new ReconciledRuntimeSession(
                            session,
                            mock(AgentDirectorySnapshotDTO.class),
                            mock(com.huawei.hicampus.claw.ai.types.Model.class),
                            List.of()));
            when(contexts.create(any(), any(), any(), any(), any(), any())).thenReturn(context);
            when(engines.withOperationLock(anyString(), any(Supplier.class)))
                    .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
            when(persistence.acceptMessage(eq("session-v2"), any(), any(), any()))
                    .thenAnswer(invocation -> captureAcceptance(invocation.getArgument(1), invocation.getArgument(2)));
        }

        private UserMessageAcceptanceDTO captureAcceptance(RuntimeEntryDTO entry, CommittedEventDTO event) {
            acceptedEntry = entry;
            acceptedEvent = event;
            acceptedEntry.setEntrySeq(1L);
            acceptedEvent.setEventSeq(1L);
            return new UserMessageAcceptanceDTO(acceptedEntry, target);
        }

        private static RuntimeSessionDTO session() {
            RuntimeSessionDTO session = new RuntimeSessionDTO();
            session.setId("session-v2");
            session.setAgentId("agent-v2");
            session.setState(RuntimeSessionState.IDLE.value());
            return session;
        }
    }

    private static final class RealContextFixture {
        private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T01:02:03.456Z"), ZoneOffset.UTC);

        private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        private final RuntimeSessionModelReconciler reconciler = mock(RuntimeSessionModelReconciler.class);
        private final RuntimeExecutionPersistenceService persistence = mock(RuntimeExecutionPersistenceService.class);
        private final RuntimeV2ExecutionCoordinator coordinator = mock(RuntimeV2ExecutionCoordinator.class);
        private final RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        private final RuntimeSessionEngineRegistry engines;
        private final RuntimeEntryCodec codec;
        private final RuntimeV2MessageEventService service;
        private RuntimeEntryDTO acceptedEntry;
        private CommittedEventDTO acceptedEvent;
        private RuntimeActiveExecution execution;

        private RealContextFixture(RuntimeSessionEngineRegistry engines) {
            this.engines = engines;
            ObjectMapper mapper = new ObjectMapper();
            var messages = new RuntimeMessageSourceConfiguration().messageSource();
            codec = new RuntimeEntryCodec(mapper, messages);
            RuntimeExecutionContextFactory contexts = new RuntimeExecutionContextFactory(
                    new RuntimeEventQueryService(
                            repository, codec, new RuntimeEventCursorCodec(new RuntimeEventProperties(), CLOCK)),
                    engines,
                    codec,
                    new RuntimeEventStreamFactory(new RuntimeEventProperties(), codec),
                    CLOCK);
            service = new RuntimeV2MessageEventService(
                    repository,
                    codec,
                    () -> "event-user",
                    engines,
                    contexts,
                    coordinator,
                    reconciler,
                    persistence,
                    new RuntimeCommittedEventFactory(mapper, messages),
                    new RuntimeV2EventEncoder(new CommittedEventProjection(mapper), mapper),
                    CLOCK);
            configure();
        }

        private void configure() {
            RuntimeSessionDTO session = Fixture.session();
            AgentDirectorySnapshotDTO snapshot = snapshot("agent-v2");
            Model model = mock(Model.class);
            when(repository.find("session-v2")).thenReturn(java.util.Optional.of(session));
            when(repository.listCurrentBranchEntries("session-v2", 0L, 500)).thenReturn(List.of());
            when(reconciler.reconcile(session))
                    .thenReturn(new ReconciledRuntimeSession(session, snapshot, model, List.of()));
            if (org.mockito.Mockito.mockingDetails(engines).isMock()) {
                when(engines.register(anyString(), any(), any(), any(Boolean.class), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            execution = invocation.getArgument(5);
                            return holder;
                        });
            }
            when(persistence.acceptMessage(eq("session-v2"), any(), any(), any()))
                    .thenAnswer(invocation -> accept(invocation.getArgument(1), invocation.getArgument(2)));
        }

        private UserMessageAcceptanceDTO accept(RuntimeEntryDTO entry, CommittedEventDTO event) {
            acceptedEntry = entry;
            acceptedEvent = event;
            acceptedEntry.setEntrySeq(1L);
            acceptedEvent.setEventSeq(1L);
            return new UserMessageAcceptanceDTO(
                    entry, new ExecutionTargetDTO("session-v2", "execution-v2", "event-user", "segment-v2"));
        }

        private UserMessage startedMessage() {
            ArgumentCaptor<UserMessage> message = ArgumentCaptor.forClass(UserMessage.class);
            verify(coordinator).start(eq(holder), eq(execution), message.capture(), eq(Locale.US));
            return message.getValue();
        }
    }
}
