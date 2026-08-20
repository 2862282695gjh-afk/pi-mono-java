/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
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
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
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
        when(repository.appendEntry(any())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(0);
            entry.setEntrySeq(sequence.getAndIncrement());
            persisted.add(entry);
            return entry;
        });
        RuntimeEventStream stream = new RuntimeEventStream(256, 1024L * 1024L, Duration.ofSeconds(15), event -> 1L);
        RuntimeActiveExecution execution = new RuntimeActiveExecution(stream);
        UserMessage initialMessage = new UserMessage("分析订单", 1L);
        AtomicInteger ids = new AtomicInteger(1);
        RuntimeEntryIdGenerator idGenerator = () -> "entry_" + ids.getAndIncrement();
        RuntimeEventProjector projector = new RuntimeEventProjector(
                "session_event_test",
                repository,
                new RuntimeEntryCodec(new ObjectMapper()),
                idGenerator,
                stream,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                agent::abort,
                execution,
                initialMessage,
                false);
        agent.subscribe(projector::onEvent);

        agent.prompt(initialMessage).get(2, TimeUnit.SECONDS);
        stream.complete();

        List<String> eventNames =
                collect(stream).stream().map(RuntimeSseEventVO::getEvent).toList();
        assertConfirmedEventOrder(eventNames);
        assertThat(persisted)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("assistant.message.completed", "tool.result", "assistant.message.completed");
        assertThat(persisted).extracting(RuntimeEntryDTO::getEntrySeq).containsExactly(2L, 3L, 4L);
        assertThat(projector.failure()).isNull();
        assertThat(projector.terminalReason()).isEqualTo(StopReason.STOP);
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

        assertThat(collect(enabledStream))
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly(
                        "assistant.message.started",
                        "assistant.thinking.started",
                        "assistant.thinking.delta",
                        "assistant.thinking.completed");
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

    private static RuntimeEventProjector projector(
            RuntimeSessionRepository repository, RuntimeEventStream stream, boolean thinking) {
        AtomicInteger ids = new AtomicInteger(1);
        return new RuntimeEventProjector(
                "session_event_test",
                repository,
                new RuntimeEntryCodec(new ObjectMapper()),
                () -> "entry_" + ids.getAndIncrement(),
                stream,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
                () -> {},
                new RuntimeActiveExecution(stream),
                new UserMessage("分析订单", 1L),
                thinking);
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
            Model model, List<com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock> content, StopReason reason, long timestamp) {
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
