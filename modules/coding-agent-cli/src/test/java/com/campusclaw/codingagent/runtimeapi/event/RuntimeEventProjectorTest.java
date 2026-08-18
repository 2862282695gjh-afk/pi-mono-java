/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

import com.campusclaw.agent.Agent;
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
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.StreamOptions;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 使用真实 pi AgentLoop 验证公共 SSE 投影和持久化事件顺序。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
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
        RuntimeEventStream stream = new RuntimeEventStream(
                256, 1024L * 1024L, Duration.ofSeconds(15), event -> 1L);
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
                initialMessage);
        agent.subscribe(projector::onEvent);

        agent.prompt(initialMessage).get(2, TimeUnit.SECONDS);
        stream.complete();

        List<String> eventNames = collect(stream).stream().map(RuntimeSseEventVO::getEvent).toList();
        assertConfirmedEventOrder(eventNames);
        assertThat(persisted)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("assistant.message.completed", "tool.result", "assistant.message.completed");
        assertThat(persisted).extracting(RuntimeEntryDTO::getEntrySeq).containsExactly(2L, 3L, 4L);
        assertThat(projector.failure()).isNull();
        assertThat(projector.terminalReason()).isEqualTo(StopReason.STOP);
    }

    private static void assertConfirmedEventOrder(List<String> eventNames) {
        assertThat(eventNames).containsExactly(
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
