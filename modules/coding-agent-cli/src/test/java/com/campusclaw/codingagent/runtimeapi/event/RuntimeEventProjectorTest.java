/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.agent.Agent;
import com.campusclaw.agent.event.MessageStartEvent;
import com.campusclaw.agent.event.MessageUpdateEvent;
import com.campusclaw.agent.event.ToolExecutionUpdateEvent;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.provider.ApiProvider;
import com.campusclaw.ai.provider.ApiProviderRegistry;
import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.stream.AssistantMessageEventStream;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.StreamOptions;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.session.compaction.CompactionReason;
import com.campusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 使用真实 pi AgentLoop 验证公共 SSE 投影和持久化事件顺序。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventProjectorTest {
    @Test
    void projectsToolCallingAgentLoopIntoConfirmedEventSequence() throws Exception {
        Model model = sampleModel();
        Agent agent = new Agent(aiService(model, new ToolThenTextProvider()));
        agent.setModel(model);
        agent.setTools(List.of(new QueryTool()));
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        AtomicInteger sequence = new AtomicInteger(2);
        List<RuntimeEntryDTO> persisted = new ArrayList<>();
        configurePersistence(repository, sequence, persisted);
        RuntimeEventStream stream = new RuntimeEventStream(256, 1024L * 1024L, Duration.ofSeconds(15), event -> 1L);
        RuntimeActiveExecution execution = new RuntimeActiveExecution(stream);
        execution.beginRun("entry_run");
        UserMessage initialMessage = new UserMessage("分析订单", 1L);
        AtomicInteger ids = new AtomicInteger(1);
        RuntimeEntryIdGenerator idGenerator = () -> "entry_" + ids.getAndIncrement();
        RuntimeEventProjector projector = new RuntimeEventProjector(
                "session_event_test",
                repository,
                codec(),
                idGenerator,
                stream,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                agent::abort,
                execution,
                initialMessage,
                false,
                Locale.US);
        agent.subscribe(projector::onEvent);

        agent.prompt(initialMessage).get(2, TimeUnit.SECONDS);
        stream.complete();

        List<RuntimeSseEventVO> events = collect(stream);
        List<String> eventNames =
                events.stream().map(RuntimeSseEventVO::getEvent).toList();
        assertConfirmedEventOrder(eventNames);
        assertCamelCaseEventData(events);
        assertThat(persisted)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("assistant.message.completed", "tool.result", "assistant.message.completed");
        assertThat(persisted).extracting(RuntimeEntryDTO::getEntrySeq).containsExactly(2L, 4L, 5L);
        assertThat(projector.failure()).isNull();
        assertThat(projector.terminalReason()).isEqualTo(StopReason.STOP);
    }

    private static void configurePersistence(
            RuntimeSessionRepository repository, AtomicInteger sequence, List<RuntimeEntryDTO> persisted) {
        when(repository.appendEntry(any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(0);
            entry.setEntrySeq(sequence.getAndIncrement());
            persisted.add(entry);
            return entry;
        });
        when(repository.appendEntryWithUsage(any(), any(), any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(0);
            RuntimeRecordDTO record = invocation.getArgument(1);
            entry.setEntrySeq(sequence.getAndIncrement());
            record.setRecordSeq(sequence.getAndIncrement());
            persisted.add(entry);
            return entry;
        });
    }

    @Test
    void projectsThinkingLifecycleOnlyForEnabledExecutionSnapshot() {
        Model model = sampleModel();
        AssistantMessage message = assistant(model, List.of(new ThinkingContent("先定位异常订单")), StopReason.STOP, 10L);
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        when(repository.appendEntry(any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(0);
            entry.setEntrySeq(2L);
            return entry;
        });
        RuntimeEventStream enabledStream = eventStream();
        RuntimeEventProjector enabled = projector(repository, enabledStream, true);

        projectThinking(enabled, message);
        enabledStream.complete();

        List<RuntimeSseEventVO> thinkingEvents = collect(enabledStream);
        assertThat(thinkingEvents)
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly(
                        "assistant.message.started",
                        "assistant.thinking.started",
                        "assistant.thinking.delta",
                        "assistant.thinking.completed");
        assertThat(event(thinkingEvents, "assistant.thinking.started").getData())
                .containsKeys("assistantEntryId", "contentIndex")
                .doesNotContainKeys("assistant_entry_id", "content_index");
        var entry = org.mockito.ArgumentCaptor.forClass(RuntimeEntryDTO.class);
        verify(repository).appendEntry(entry.capture());
        assertThat(entry.getValue().getType()).isEqualTo("assistant.thinking.completed");
        assertThat(entry.getValue().getPayload()).contains("先定位异常订单", "assistant_entry_id");

        RuntimeEventStream disabledStream = eventStream();
        RuntimeEventProjector disabled = projector(mock(RuntimeSessionRepository.class), disabledStream, false);
        projectThinking(disabled, message);
        disabledStream.complete();
        assertThat(collect(disabledStream))
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly("assistant.message.started");
    }

    @Test
    void projectsToolDeltaWithoutArgumentsOrPersistence() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeEventStream stream = eventStream();
        RuntimeEventProjector projector = projector(repository, stream, false);

        projector.onEvent(new ToolExecutionUpdateEvent(
                "call_201", "Read", Map.of("path", "/secret/input.txt"), Map.of("line", "partial")));
        stream.complete();

        RuntimeSseEventVO delta = event(collect(stream), "tool.execution.delta");
        assertThat(delta.getData())
                .containsEntry("toolCallId", "call_201")
                .containsEntry("toolName", "Read")
                .containsEntry("delta", Map.of("line", "partial"))
                .doesNotContainKeys("args", "path");
        verify(repository, org.mockito.Mockito.never()).appendEntry(any());
    }

    @Test
    void projectsAbortedCompactionFailureAsTransientEvent() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeEventStream stream = eventStream();
        RuntimeEventProjector projector = projector(repository, stream, false);

        projector.onCompactionEvent(
                new SessionCompactionFailedEvent(CompactionReason.OVERFLOW, true, true, "compaction failed"));
        stream.complete();

        RuntimeSseEventVO failed = event(collect(stream), "session.compaction.failed");
        assertThat(failed.getId()).isNull();
        assertThat(failed.getData())
                .containsEntry("reason", "overflow")
                .containsEntry("willRetry", true)
                .containsEntry("aborted", true)
                .containsEntry("message", "compaction failed");
        verify(repository, org.mockito.Mockito.never()).appendEntry(any());
    }

    @Test
    void reloadExcludesLengthResponseDiscardedBeforeCompactionRetry() {
        Model model = sampleModel();
        RuntimeEntryCodec codec = codec();
        OffsetDateTime time = OffsetDateTime.parse("2026-08-24T14:00:00Z");
        RuntimeEntryDTO user = codec.userEntry("session_event_test", "entry_user", "task", List.of(), time);
        user.setEntrySeq(1L);
        AssistantMessage length = assistant(model, List.of(new TextContent("partial")), StopReason.LENGTH, 2L);
        RuntimeEntryDTO discarded = codec.assistantEntry("session_event_test", "entry_length", length, time);
        discarded.setEntrySeq(2L);
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        when(repository.listCurrentBranchEntries("session_event_test", 0L, 500)).thenReturn(List.of(user, discarded));
        AtomicReference<RuntimeEntryDTO> compaction = new AtomicReference<>();
        when(repository.appendEntryWithUsage(any(), any(), any()))
                .thenAnswer(invocation -> persistCompaction(invocation.getArgument(0), compaction));
        RuntimeEventStream stream = eventStream();
        RuntimeEventProjector projector = projector(repository, stream, false, codec);
        SessionCompactionResult result =
                new SessionCompactionResult("summary", List.of(new UserMessage("task", 1L)), 0, 100, 20, Usage.empty());

        projector.onCompactionEvent(new SessionCompactionCompletedEvent(CompactionReason.OVERFLOW, result, true));
        RuntimeEntryDTO retry = codec.assistantEntry(
                "session_event_test",
                "entry_retry",
                assistant(model, List.of(new TextContent("done")), StopReason.STOP, 3L),
                time);
        List<Message> restored = codec.toAgentMessages(List.of(user, discarded, compaction.get(), retry), model);
        stream.complete();

        assertThat(compaction.get().getPayload()).contains("\"_discardedEntryId\":\"entry_length\"");
        assertThat(codec.toAgentContextEntryIds(List.of(user, discarded, compaction.get(), retry)))
                .containsExactly(compaction.get().getId(), "entry_user", "entry_retry");
        List<AssistantMessage> assistants = restored.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .toList();
        assertThat(assistants).hasSize(1);
        assertThat(((TextContent) assistants.getFirst().content().getFirst()).text())
                .isEqualTo("done");
        assertThat(event(collect(stream), "session.compaction.completed").getData())
                .doesNotContainKey("_discardedEntryId");
    }

    private static RuntimeEventProjector projector(
            RuntimeSessionRepository repository, RuntimeEventStream stream, boolean thinking) {
        return projector(repository, stream, thinking, codec());
    }

    private static RuntimeEventProjector projector(
            RuntimeSessionRepository repository, RuntimeEventStream stream, boolean thinking, RuntimeEntryCodec codec) {
        AtomicInteger ids = new AtomicInteger(1);
        RuntimeActiveExecution execution = new RuntimeActiveExecution(stream);
        execution.beginRun("entry_run");
        return new RuntimeEventProjector(
                "session_event_test",
                repository,
                codec,
                () -> "entry_" + ids.getAndIncrement(),
                stream,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                () -> {},
                execution,
                new UserMessage("分析订单", 1L),
                thinking,
                Locale.US);
    }

    private static RuntimeEntryDTO persistCompaction(
            RuntimeEntryDTO entry, AtomicReference<RuntimeEntryDTO> compaction) {
        entry.setEntrySeq(3L);
        compaction.set(entry);
        return entry;
    }

    private static void projectThinking(RuntimeEventProjector projector, AssistantMessage message) {
        projector.onEvent(new MessageStartEvent(message));
        projector.onEvent(new MessageUpdateEvent(message, new AssistantMessageEvent.ThinkingStartEvent(0, message)));
        projector.onEvent(
                new MessageUpdateEvent(message, new AssistantMessageEvent.ThinkingDeltaEvent(0, "先定位", message)));
        projector.onEvent(
                new MessageUpdateEvent(message, new AssistantMessageEvent.ThinkingEndEvent(0, "先定位异常订单", message)));
    }

    private static RuntimeEventStream eventStream() {
        return new RuntimeEventStream(16, 4096, Duration.ofSeconds(15), event -> 1L);
    }

    private static RuntimeEntryCodec codec() {
        return new RuntimeEntryCodec(
                new ObjectMapper(),
                new com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration().messageSource());
    }

    private static void assertConfirmedEventOrder(List<String> eventNames) {
        assertThat(eventNames)
                .containsExactly(
                        "assistant.message.started",
                        "assistant.message.completed",
                        "tool.execution.started",
                        "tool.execution.completed",
                        "tool.result",
                        "assistant.message.started",
                        "assistant.message.delta",
                        "assistant.message.completed");
    }

    private static void assertCamelCaseEventData(List<RuntimeSseEventVO> events) {
        assertThat(event(events, "assistant.message.completed").getData())
                .containsKeys("entryId", "entrySeq", "finishReason", "createdAt")
                .doesNotContainKeys("entry_id", "entry_seq", "finish_reason", "created_at");
        assertThat(event(events, "tool.execution.started").getData())
                .containsKeys("toolCallId", "toolName")
                .doesNotContainKeys("tool_call_id", "tool_name");
        assertThat(event(events, "tool.execution.completed").getData())
                .containsKeys("toolCallId", "toolName", "isError")
                .doesNotContainKeys("tool_call_id", "tool_name", "is_error");
    }

    private static RuntimeSseEventVO event(List<RuntimeSseEventVO> events, String name) {
        return events.stream()
                .filter(event -> name.equals(event.getEvent()))
                .findFirst()
                .orElseThrow();
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

    private static AssistantMessageEventStream toolCallStream(Model model) {
        ToolCall call = new ToolCall("call_201", "query_abnormal_orders", Map.of("scope", "uploaded_files"));
        AssistantMessage message =
                assistant(model, List.of(new TextContent("我先查询异常订单。"), call), StopReason.TOOL_USE, 10L);
        AssistantMessageEventStream stream = new AssistantMessageEventStream();
        stream.push(new AssistantMessageEvent.StartEvent(message));
        stream.push(new AssistantMessageEvent.ToolCallEndEvent(1, call, message));
        stream.push(new AssistantMessageEvent.DoneEvent(StopReason.TOOL_USE, message));
        return stream;
    }

    private static AssistantMessageEventStream textStream(Model model) {
        AssistantMessage message = assistant(model, List.of(new TextContent("发现 3 条异常订单。")), StopReason.STOP, 20L);
        AssistantMessageEventStream stream = new AssistantMessageEventStream();
        stream.push(new AssistantMessageEvent.StartEvent(message));
        stream.push(new AssistantMessageEvent.TextDeltaEvent(0, "发现 3 条异常订单。", message));
        stream.push(new AssistantMessageEvent.DoneEvent(StopReason.STOP, message));
        return stream;
    }

    private static AssistantMessage assistant(
            Model model, List<com.campusclaw.ai.types.ContentBlock> content, StopReason reason, long timestamp) {
        return new AssistantMessage(
                content,
                model.api().value(),
                model.provider().value(),
                model.id(),
                null,
                Usage.empty(),
                reason,
                null,
                timestamp);
    }

    private static final class ToolThenTextProvider implements ApiProvider {
        private final AtomicInteger calls = new AtomicInteger();

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
            return calls.getAndIncrement() == 0 ? toolCallStream(model) : textStream(model);
        }
    }

    private static final class QueryTool implements AgentTool {
        @Override
        public String name() {
            return "query_abnormal_orders";
        }

        @Override
        public String label() {
            return name();
        }

        @Override
        public String description() {
            return "查询异常订单";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return new ObjectMapper().createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            return new AgentToolResult(List.of(new TextContent("发现 3 条异常订单。")), null);
        }
    }
}
