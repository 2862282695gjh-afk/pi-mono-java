/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry;
import com.huawei.hicampus.mate.matecampusclaw.ai.provider.ApiProvider;
import com.huawei.hicampus.mate.matecampusclaw.ai.provider.ApiProviderRegistry;
import com.huawei.hicampus.mate.matecampusclaw.ai.stream.AssistantMessageEvent;
import com.huawei.hicampus.mate.matecampusclaw.ai.stream.AssistantMessageEventStream;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Api;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Context;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.SimpleStreamOptions;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StreamOptions;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CredentialMode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance.Status;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;

/**
 * Runtime Event Service 的执行生命周期与 SSE 断线语义测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeEventServiceTest {
    private static final String SESSION_ID = "session_event_service";

    private static final String OWNER_ID = "mate-service";

    @Test
    void clientCancellationDoesNotAbortAcceptedExecution() throws Exception {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeSessionDTO session = session();
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
        List<RuntimeEntryDTO> persisted = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger(1);
        when(repository.acceptUserEvent(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(2);
            entry.setEntrySeq(sequence.getAndIncrement());
            persisted.add(entry);
            return new UserEventAcceptance(Status.ACCEPTED, session);
        });
        when(repository.appendEntry(any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(0);
            entry.setEntrySeq(sequence.getAndIncrement());
            persisted.add(entry);
            return entry;
        });
        BlockingTextProvider provider = new BlockingTextProvider();
        Model model = sampleModel();
        Agent agent = new Agent(aiService(model, provider));
        agent.setModel(model);
        RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        RuntimeSessionHolder holder = new RuntimeSessionHolder(SESSION_ID, null, agent);
        when(engines.find(SESSION_ID)).thenReturn(Optional.of(holder));
        RuntimeEventService service = service(repository, engines);

        var events = service.submit(SESSION_ID, caller(), request("分析订单"), false);
        assertThat(events.take(1).blockFirst(Duration.ofSeconds(2)).getEvent()).isEqualTo("user.message");
        assertThat(provider.entered.await(2, TimeUnit.SECONDS)).isTrue();
        provider.release.countDown();
        agent.waitForIdle().get(2, TimeUnit.SECONDS);
        verify(repository, timeout(2_000)).finishExecution(anyString(), any());

        assertThat(events.map(event -> event.getEvent()).collectList().block(Duration.ofSeconds(2)))
                .containsExactly(
                        "user.message",
                        "assistant.message.started",
                        "assistant.message.delta",
                        "assistant.message.completed",
                        "session.status.idle",
                        "stream.end");
        assertThat(persisted)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("user.message", "assistant.message.completed");
        assertThat(holder.activeExecution()).isEmpty();
        verify(repository, times(2)).find(SESSION_ID);
    }

    @Test
    void executionFailureEmitsStreamErrorWithoutSuccessfulEnd() throws Exception {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeSessionDTO session = session();
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
        when(repository.acceptUserEvent(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(2);
            entry.setEntrySeq(1L);
            return new UserEventAcceptance(Status.ACCEPTED, session);
        });
        BlockingErrorProvider provider = new BlockingErrorProvider();
        Model model = sampleModel();
        Agent agent = new Agent(aiService(model, provider));
        agent.setModel(model);
        RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        RuntimeSessionHolder holder = new RuntimeSessionHolder(SESSION_ID, null, agent);
        when(engines.find(SESSION_ID)).thenReturn(Optional.of(holder));

        var events = service(repository, engines).submit(SESSION_ID, caller(), request("分析订单"), false);
        assertThat(provider.entered.await(2, TimeUnit.SECONDS)).isTrue();
        provider.release.countDown();

        assertThat(events.map(event -> event.getEvent()).collectList().block(Duration.ofSeconds(2)))
                .containsExactly("user.message", "stream.error")
                .doesNotContain("session.status.idle", "stream.end");
        verify(repository, timeout(2_000)).finishExecution(anyString(), any());
        assertThat(holder.activeExecution()).isEmpty();
    }

    @Test
    void synchronousAgentStartFailureReturnsAcceptedStreamToIdle() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeSessionDTO session = session();
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
        when(repository.acceptUserEvent(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(2);
            entry.setEntrySeq(1L);
            return new UserEventAcceptance(Status.ACCEPTED, session);
        });
        Agent agent = mock(Agent.class);
        when(agent.subscribe(any())).thenReturn(() -> {});
        when(agent.prompt(any(com.huawei.hicampus.mate.matecampusclaw.ai.types.Message.class)))
                .thenThrow(new IllegalStateException("simulated synchronous start failure"));
        RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        RuntimeSessionHolder holder = new RuntimeSessionHolder(SESSION_ID, null, agent);
        when(engines.find(SESSION_ID)).thenReturn(Optional.of(holder));

        var events = service(repository, engines).submit(SESSION_ID, caller(), request("分析订单"), false);

        assertThat(events.map(event -> event.getEvent()).collectList().block(Duration.ofSeconds(2)))
                .containsExactly("user.message", "stream.error")
                .doesNotContain("session.status.idle", "stream.end");
        verify(repository).finishExecution(anyString(), any());
        assertThat(holder.activeExecution()).isEmpty();
    }

    @Test
    void rejectsDuplicateFileIdsBeforeReadingSession() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        UserEventRequestVO request = request(null);
        request.setFileIds(List.of("file_same", "file_same"));
        RuntimeEventService service = service(repository, mock(RuntimeSessionEngineRegistry.class));

        assertThatThrownBy(() -> service.submit(SESSION_ID, caller(), request, false))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_EVENT_REQUEST));
    }

    @Test
    void mapsSessionLookupFailureToAcceptanceErrorBeforeStream() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        when(repository.find(SESSION_ID)).thenThrow(new IllegalStateException("database unavailable"));
        RuntimeEventService service = service(repository, mock(RuntimeSessionEngineRegistry.class));

        assertThatThrownBy(() -> service.submit(SESSION_ID, caller(), request("分析订单"), false))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED));
    }

    @Test
    void listsOnlyRequestedPageAndIssuesOpaqueCursor() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session()));
        RuntimeEntryDTO first = historyEntry("entry_1", 1L, "first");
        RuntimeEntryDTO second = historyEntry("entry_2", 2L, "second");
        when(repository.listCurrentBranch(SESSION_ID, 0L, 2)).thenReturn(List.of(first, second));
        RuntimeEventService service = service(repository, mock(RuntimeSessionEngineRegistry.class));

        var page = service.list(SESSION_ID, caller(), "1", null);

        assertThat(page.getEvents()).hasSize(1);
        assertThat(page.getEvents().getFirst())
                .containsEntry("type", "user.message")
                .containsEntry("entry_id", "entry_1")
                .containsEntry("entry_seq", 1L)
                .containsEntry("message", "first");
        assertThat(page.getNextPage()).startsWith("page_").doesNotContain(SESSION_ID);
    }

    @Test
    void mapsSessionLookupFailureToEventListError() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        when(repository.find(SESSION_ID)).thenThrow(new IllegalStateException("database unavailable"));
        RuntimeEventService service = service(repository, mock(RuntimeSessionEngineRegistry.class));

        assertThatThrownBy(() -> service.list(SESSION_ID, caller(), null, null))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_LIST_FAILED));
    }

    private static RuntimeEventService service(
            RuntimeSessionRepository repository, RuntimeSessionEngineRegistry engines) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);
        RuntimeEventProperties properties = new RuntimeEventProperties();
        properties.setCursorSecret("runtime-event-service-test-secret");
        RuntimeEntryCodec codec = new RuntimeEntryCodec(new ObjectMapper());
        AtomicInteger ids = new AtomicInteger(1);
        return new RuntimeEventService(
                repository,
                codec,
                () -> "entry_" + ids.getAndIncrement(),
                (sessionId, fileIds) -> List.of(),
                mock(AgentRuntimeSnapshotProvider.class),
                mock(RuntimeModelManager.class),
                engines,
                new RuntimeEventCursorCodec(properties, clock),
                Validation.buildDefaultValidatorFactory().getValidator(),
                clock);
    }

    private static UserEventRequestVO request(String message) {
        UserEventRequestVO request = new UserEventRequestVO();
        request.setType("user.message");
        request.setMessage(message);
        request.setFileIds(List.of());
        return request;
    }

    private static RuntimeEntryDTO historyEntry(String entryId, long sequence, String message) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId(SESSION_ID);
        entry.setId(entryId);
        entry.setEntrySeq(sequence);
        entry.setType("user.message");
        entry.setTimestamp(OffsetDateTime.parse("2026-08-18T00:00:00Z"));
        entry.setPayload("{\"message\":\"" + message + "\",\"file_ids\":[]}");
        return entry;
    }

    private static CallerAuthContext caller() {
        return new CallerAuthContext(OWNER_ID, CredentialMode.JWT);
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(SESSION_ID);
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setOwnerId(OWNER_ID);
        session.setBundleRevision("revision-test");
        session.setModelId("test-model");
        session.setState("idle");
        session.setResourceVersion(1L);
        session.setCreatedAt(OffsetDateTime.parse("2026-08-18T00:00:00Z"));
        session.setUpdatedAt(session.getCreatedAt());
        return session;
    }

    private static CampusClawAiService aiService(Model model, ApiProvider provider) {
        ApiProviderRegistry providers = new ApiProviderRegistry(List.of(provider));
        ModelRegistry models = new ModelRegistry();
        models.register(model);
        return new CampusClawAiService(providers, models);
    }

    private static Model sampleModel() {
        return new Model(
                "test-model",
                "Test Model",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(1.0, 2.0, 0.5, 0.25),
                200_000,
                4_096,
                null,
                null,
                null);
    }

    private static final class BlockingTextProvider implements ApiProvider {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Api getApi() {
            return Api.ANTHROPIC_MESSAGES;
        }

        @Override
        public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
            throw new UnsupportedOperationException("Agent uses streamSimple");
        }

        @Override
        public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
            AssistantMessageEventStream stream = new AssistantMessageEventStream();
            entered.countDown();
            Thread.ofVirtual().start(() -> emitAfterRelease(stream, model));
            return stream;
        }

        private void emitAfterRelease(AssistantMessageEventStream stream, Model model) {
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                AssistantMessage message = new AssistantMessage(
                        List.of(new TextContent("分析完成")),
                        model.api().value(),
                        model.provider().value(),
                        model.id(),
                        null,
                        Usage.empty(),
                        StopReason.STOP,
                        null,
                        1L);
                stream.push(new AssistantMessageEvent.StartEvent(message));
                stream.push(new AssistantMessageEvent.TextDeltaEvent(0, "分析完成", message));
                stream.push(new AssistantMessageEvent.DoneEvent(StopReason.STOP, message));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                stream.error(error);
            }
        }
    }

    private static final class BlockingErrorProvider implements ApiProvider {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Api getApi() {
            return Api.ANTHROPIC_MESSAGES;
        }

        @Override
        public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
            throw new UnsupportedOperationException("Agent uses streamSimple");
        }

        @Override
        public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
            AssistantMessageEventStream stream = new AssistantMessageEventStream();
            entered.countDown();
            Thread.ofVirtual().start(() -> failAfterRelease(stream));
            return stream;
        }

        private void failAfterRelease(AssistantMessageEventStream stream) {
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                stream.error(new IllegalStateException("simulated model failure"));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                stream.error(error);
            }
        }
    }
}
