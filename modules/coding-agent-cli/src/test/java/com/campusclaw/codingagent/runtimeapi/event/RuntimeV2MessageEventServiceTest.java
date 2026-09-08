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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeExecutionContextDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.UserMessageAcceptanceDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.session.ReconciledRuntimeSession;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageContentRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageContentRequestVO.FileRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageContentRequestVO.TextRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageEventRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * v2 普通消息原子受理、公开内容隔离和首帧顺序测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeV2MessageEventServiceTest {
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
        List<UserMessageContentRequestVO> content = new ArrayList<>();
        TextRequestVO textBlock = new TextRequestVO();
        textBlock.setText(text);
        content.add(textBlock);
        if (fileId != null) {
            FileRequestVO file = new FileRequestVO();
            file.setFileId(fileId);
            content.add(file);
        }
        UserMessageEventRequestVO request = new UserMessageEventRequestVO();
        request.setContent(content);
        return request;
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
                            mock(com.campusclaw.ai.types.Model.class),
                            List.of()));
            when(contexts.create(any(), any(), any(), any(), any(), any())).thenReturn(context);
            when(engines.withOperationLock(anyString(), any(Supplier.class)))
                    .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
            when(persistence.acceptMessage(eq("session-v2"), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        acceptedEntry = invocation.getArgument(1);
                        acceptedEvent = invocation.getArgument(2);
                        acceptedEntry.setEntrySeq(1L);
                        acceptedEvent.setEventSeq(1L);
                        return new UserMessageAcceptanceDTO(acceptedEntry, target);
                    });
        }

        private static RuntimeSessionDTO session() {
            RuntimeSessionDTO session = new RuntimeSessionDTO();
            session.setId("session-v2");
            session.setAgentId("agent-v2");
            session.setState(RuntimeSessionState.IDLE.value());
            return session;
        }
    }
}
