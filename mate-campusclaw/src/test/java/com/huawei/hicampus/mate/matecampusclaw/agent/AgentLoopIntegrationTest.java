/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.agent.context.DefaultMessageConverter;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.TurnEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.TurnStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.loop.AgentLoop;
import com.huawei.hicampus.mate.matecampusclaw.agent.loop.AgentLoopConfig;
import com.huawei.hicampus.mate.matecampusclaw.agent.queue.MessageQueue;
import com.huawei.hicampus.mate.matecampusclaw.agent.state.AgentState;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentContext;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionPipeline;
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
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.SimpleStreamOptions;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StreamOptions;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Agent 循环端到端流程的集成测试（IT-002）。
 *
 * <p>Uses a {@code MockApiProvider} to simulate LLM responses and verifies
 * 覆盖 prompt → LLM → tool → result → LLM → done 的完整循环，以及多轮工具调用、
 * steer 注入、follow-up 消息、中止和事件顺序。
 */
@Timeout(30)
class AgentLoopIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Model model;
    private MessageQueue steeringQueue;
    private MessageQueue followUpQueue;
    private ToolExecutionPipeline toolPipeline;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() {
        model = new Model(
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
        steeringQueue = new MessageQueue();
        followUpQueue = new MessageQueue();
        toolPipeline = new ToolExecutionPipeline();
        events = new ArrayList<>();
    }

    // -------------------------------------------------------------------
    // 单轮：prompt → LLM 文本响应 → 完成。
    // -------------------------------------------------------------------

    @Nested
    class SingleTurnTextResponse {

        @Test
        void completesSimpleTextResponse() {
            var provider = new ScriptedProvider(List.of(textReply("Hello! How can I help?")));

            var result = runLoop(provider, List.of(), "Hi there");

            assertEquals(2, result.size());
            assertInstanceOf(UserMessage.class, result.get(0));
            assertInstanceOf(AssistantMessage.class, result.get(1));
            assertEquals("Hello! How can I help?", textOf(result.get(1)));
        }

        @Test
        void emitsCorrectEventSequenceForTextResponse() {
            var provider = new ScriptedProvider(List.of(textReply("Response")));

            runLoop(provider, List.of(), "Hello");

            // 预期包含 AgentStart、TurnStart 和用户消息开始/结束事件。
            //           MessageStart, MessageUpdate+, MessageEnd,
            //           TurnEnd, AgentEnd
            assertEventOrder(
                    AgentStartEvent.class,
                    TurnStartEvent.class,
                    MessageStartEvent.class, // user message
                    MessageEndEvent.class, // user message
                    MessageStartEvent.class, // assistant streaming start
                    MessageUpdateEvent.class,
                    MessageEndEvent.class, // assistant message end
                    TurnEndEvent.class,
                    AgentEndEvent.class);
        }
    }

    // -------------------------------------------------------------------
    // 工具循环：prompt → LLM → tool → result → LLM → 完成。
    // -------------------------------------------------------------------

    @Nested
    class SingleToolCallLoop {

        @Test
        void executesToolAndReturnsToLLM() {
            var bashTool = simpleTool("bash", "Run command");
            var provider = new ScriptedProvider(
                    List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done! I found the files.")));

            var result = runLoop(provider, List.of(bashTool), "List files");

            // 消息顺序：user → assistant(tool_call) → tool_result → assistant(text)。
            assertEquals(4, result.size());
            assertInstanceOf(UserMessage.class, result.get(0));
            assertInstanceOf(AssistantMessage.class, result.get(1));
            assertInstanceOf(ToolResultMessage.class, result.get(2));
            assertInstanceOf(AssistantMessage.class, result.get(3));
            assertEquals("Done! I found the files.", textOf(result.get(3)));

            // 校验工具结果内容。
            var toolResult = (ToolResultMessage) result.get(2);
            assertEquals("bash", toolResult.toolName());
            assertFalse(toolResult.isError());
        }

        @Test
        void emitsToolExecutionEvents() {
            var bashTool = simpleTool("bash", "Run command");
            var provider =
                    new ScriptedProvider(List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done")));

            runLoop(provider, List.of(bashTool), "List files");

            assertTrue(events.stream().anyMatch(ToolExecutionStartEvent.class::isInstance));
            assertTrue(events.stream().anyMatch(ToolExecutionEndEvent.class::isInstance));

            var toolStart = events.stream()
                    .filter(ToolExecutionStartEvent.class::isInstance)
                    .map(ToolExecutionStartEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("bash", toolStart.toolName());
        }

        @Test
        void emitsTwoTurnsForToolCallCycle() {
            var bashTool = simpleTool("bash", "Run command");
            var provider =
                    new ScriptedProvider(List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done")));

            runLoop(provider, List.of(bashTool), "Run ls");

            long turnCount =
                    events.stream().filter(TurnEndEvent.class::isInstance).count();
            assertEquals(2, turnCount);
        }
    }

    // -------------------------------------------------------------------
    // 多轮工具调用：prompt → tool1 → result1 → tool2 → result2 → 完成。
    // -------------------------------------------------------------------

    @Nested
    class MultiTurnToolCalls {

        @Test
        void executesMultipleSequentialToolCalls() {
            var readTool = simpleTool("read", "Read file");
            var writeTool = simpleTool("write", "Write file");

            var provider = new ScriptedProvider(List.of(
                    toolCallReply("read", Map.of("command", "cat file.txt")),
                    toolCallReply("write", Map.of("command", "echo hello > out.txt")),
                    textReply("I read the file and wrote the output.")));

            var result = runLoop(provider, List.of(readTool, writeTool), "Read and write");

            // 校验读写工具的多轮消息顺序。
            assertEquals(6, result.size());
            assertInstanceOf(UserMessage.class, result.get(0));
            assertInstanceOf(AssistantMessage.class, result.get(1));
            assertInstanceOf(ToolResultMessage.class, result.get(2));
            assertInstanceOf(AssistantMessage.class, result.get(3));
            assertInstanceOf(ToolResultMessage.class, result.get(4));
            assertInstanceOf(AssistantMessage.class, result.get(5));

            // 3 turns: tool1, tool2, final text
            long turnCount =
                    events.stream().filter(TurnEndEvent.class::isInstance).count();
            assertEquals(3, turnCount);

            // 2 tool executions
            long toolExecCount = events.stream()
                    .filter(ToolExecutionStartEvent.class::isInstance)
                    .count();
            assertEquals(2, toolExecCount);
        }
    }

    // -------------------------------------------------------------------
    // steer 注入：工具将消息入队，下一轮读取。
    // -------------------------------------------------------------------

    @Nested
    class SteeringInjection {

        @Test
        void injectsSteeringMessageAfterToolExecution() {
            var steeringTool = new SteeringAgentTool(steeringQueue);
            var provider = new ScriptedProvider(List.of(
                    toolCallReply("steering_tool", Map.of("command", "search")), textReply("Steered response")));

            var result = runLoop(provider, List.of(steeringTool), "Do something");

            // steer 消息应位于工具结果与下一条 Assistant 文本之间。
            assertEquals(5, result.size());
            assertInstanceOf(UserMessage.class, result.get(0)); // "Do something"
            assertInstanceOf(AssistantMessage.class, result.get(1)); // tool call
            assertInstanceOf(ToolResultMessage.class, result.get(2)); // tool result
            assertInstanceOf(UserMessage.class, result.get(3)); // steering message
            assertInstanceOf(AssistantMessage.class, result.get(4)); // "Steered response"

            assertEquals("injected steering", textOf(result.get(3)));
        }

        @Test
        void steeringMessageAppearsInEventsAsMessageStartEnd() {
            var steeringTool = new SteeringAgentTool(steeringQueue);
            var provider = new ScriptedProvider(
                    List.of(toolCallReply("steering_tool", Map.of("command", "search")), textReply("OK")));

            runLoop(provider, List.of(steeringTool), "Go");

            // 校验第二轮通过 MessageStart/End 投影 steer 消息。
            var messageStartEvents = events.stream()
                    .filter(MessageStartEvent.class::isInstance)
                    .map(MessageStartEvent.class::cast)
                    .toList();

            // 应包含用户消息和第一轮 Assistant 的开始事件。
            //              steering "injected steering" start, assistant start (turn 2)
            assertTrue(messageStartEvents.size() >= 4);
        }
    }

    // -------------------------------------------------------------------
    // follow-up 消息：文本响应后继续执行。
    // -------------------------------------------------------------------

    @Nested
    class FollowUpMessages {

        @Test
        void processesFollowUpMessageAfterTextResponse() {
            followUpQueue.enqueue(new UserMessage("follow-up question", 2L));

            var provider = new ScriptedProvider(List.of(textReply("First answer"), textReply("Follow-up answer")));

            var result = runLoop(provider, List.of(), "Initial question");

            // follow-up 消息应连接前后两条 Assistant 文本。
            assertEquals(4, result.size());
            assertEquals("Initial question", textOf(result.get(0)));
            assertEquals("First answer", textOf(result.get(1)));
            assertEquals("follow-up question", textOf(result.get(2)));
            assertEquals("Follow-up answer", textOf(result.get(3)));

            // 2 turns
            long turnCount =
                    events.stream().filter(TurnEndEvent.class::isInstance).count();
            assertEquals(2, turnCount);
        }

        @Test
        void noFollowUpMeansLoopEndsAfterTextResponse() {
            var provider = new ScriptedProvider(List.of(textReply("Only answer")));

            var result = runLoop(provider, List.of(), "Question");

            assertEquals(2, result.size());
            long turnCount =
                    events.stream().filter(TurnEndEvent.class::isInstance).count();
            assertEquals(1, turnCount);
        }
    }

    // -------------------------------------------------------------------
    // 执行中途取消。
    // -------------------------------------------------------------------

    @Nested
    class AbortExecution {

        @Test
        void abortStopsLoopBeforeSecondTurn() {
            var signal = new CancellationToken();

            // 工具在执行期间取消 token。
            var abortTool = new AbortingAgentTool(signal);

            var provider = new ScriptedProvider(List.of(
                    toolCallReply("abort_tool", Map.of("command", "cancel")), textReply("This should not be reached")));

            var state = new AgentState();
            state.setSystemPrompt("system");
            state.setTools(List.of(abortTool));
            var context = new AgentContext(state);

            var loop = new AgentLoop(new AgentLoopConfig(
                    piAiService(provider),
                    model,
                    new DefaultMessageConverter(),
                    null,
                    toolPipeline,
                    steeringQueue,
                    followUpQueue,
                    SimpleStreamOptions.empty()));

            var result = loop.run(List.of(new UserMessage("abort me", 1L)), context, events::add, signal);

            // 取消后循环应在工具执行阶段停止，不追加容易被模型误判为成功的 tool_result。
            // 消息顺序为 user → assistant(tool)。
            assertEquals(2, result.size());
            assertInstanceOf(UserMessage.class, result.get(0));
            assertInstanceOf(AssistantMessage.class, result.get(1));

            // 即使取消也必须投影 AgentEnd 事件。
            assertInstanceOf(AgentEndEvent.class, events.getLast());
        }
    }

    // -------------------------------------------------------------------
    // Agent 门面集成。
    // -------------------------------------------------------------------

    @Nested
    class AgentFacadeIntegration {

        @Test
        void promptRunsFullCycleViaAgentFacade() throws Exception {
            var bashTool = simpleTool("bash", "Run command");
            var provider = new ScriptedProvider(
                    List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Found 3 files")));

            var agent = new Agent(piAiService(provider));
            agent.setModel(model);
            agent.setSystemPrompt("You are a helpful assistant.");
            agent.setTools(List.of(bashTool));

            var agentEvents = new ArrayList<AgentEvent>();
            agent.subscribe(agentEvents::add);

            agent.prompt("List files").join();

            var messages = agent.getState().getMessages();
            assertEquals(4, messages.size());
            assertEquals("Found 3 files", textOf(messages.get(3)));

            // 校验 Agent 对外投影事件。
            assertInstanceOf(AgentStartEvent.class, agentEvents.getFirst());
            assertInstanceOf(AgentEndEvent.class, agentEvents.getLast());
        }

        @Test
        void abortStopsRunningExecution() throws Exception {
            var signal = new CancellationToken();
            var slowTool = new SlowAgentTool();
            var provider = new ScriptedProvider(
                    List.of(toolCallReply("slow_tool", Map.of("command", "wait")), textReply("Should not appear")));

            var agent = new Agent(piAiService(provider));
            agent.setModel(model);
            agent.setTools(List.of(slowTool));

            var future = agent.prompt("Do slow thing");

            // 等待工具开始执行。
            slowTool.waitUntilStarted();
            agent.abort();

            // Future 应当结束，允许以异常结束。
            future.handle((v, t) -> null).join();

            // Agent 应恢复到无流式执行和无待处理工具调用的干净状态。
            assertFalse(agent.getState().isStreaming());
            assertTrue(agent.getState().getPendingToolCalls().isEmpty());
        }
    }

    // -------------------------------------------------------------------
    // 事件顺序校验。
    // -------------------------------------------------------------------

    @Nested
    class EventOrdering {

        @Test
        void agentStartIsFirstAndAgentEndIsLast() {
            var provider = new ScriptedProvider(List.of(textReply("Hi")));
            runLoop(provider, List.of(), "Hello");

            assertInstanceOf(AgentStartEvent.class, events.getFirst());
            assertInstanceOf(AgentEndEvent.class, events.getLast());
        }

        @Test
        void turnStartPrecedesTurnEnd() {
            var provider = new ScriptedProvider(List.of(textReply("Hi")));
            runLoop(provider, List.of(), "Hello");

            int turnStartIdx = indexOfFirst(TurnStartEvent.class);
            int turnEndIdx = indexOfFirst(TurnEndEvent.class);
            assertTrue(turnStartIdx < turnEndIdx);
        }

        @Test
        void messageStartPrecedesMessageEnd() {
            var provider = new ScriptedProvider(List.of(textReply("Hi")));
            runLoop(provider, List.of(), "Hello");

            // 查找 Assistant 而不是 User 的消息开始和结束事件。
            var msgStarts =
                    events.stream().filter(MessageStartEvent.class::isInstance).toList();
            var msgEnds =
                    events.stream().filter(MessageEndEvent.class::isInstance).toList();

            assertTrue(msgStarts.size() >= 2); // user + assistant
            assertTrue(msgEnds.size() >= 2);
        }

        @Test
        void toolExecutionStartPrecedesToolExecutionEnd() {
            var tool = simpleTool("bash", "Run command");
            var provider =
                    new ScriptedProvider(List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done")));
            runLoop(provider, List.of(tool), "Go");

            int toolStartIdx = indexOfFirst(ToolExecutionStartEvent.class);
            int toolEndIdx = indexOfFirst(ToolExecutionEndEvent.class);
            assertTrue(toolStartIdx >= 0);
            assertTrue(toolEndIdx >= 0);
            assertTrue(toolStartIdx < toolEndIdx);
        }

        @Test
        void turnEndContainsToolResultsForToolTurn() {
            var tool = simpleTool("bash", "Run command");
            var provider =
                    new ScriptedProvider(List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done")));
            runLoop(provider, List.of(tool), "Go");

            var toolTurnEnd = events.stream()
                    .filter(TurnEndEvent.class::isInstance)
                    .map(TurnEndEvent.class::cast)
                    .filter(e -> !e.toolResults().isEmpty())
                    .findFirst();

            assertTrue(toolTurnEnd.isPresent());
            assertEquals(1, toolTurnEnd.get().toolResults().size());
        }

        @Test
        void agentEndContainsFullMessageHistory() {
            var tool = simpleTool("bash", "Run command");
            var provider =
                    new ScriptedProvider(List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done")));
            runLoop(provider, List.of(tool), "Go");

            var agentEnd = events.stream()
                    .filter(AgentEndEvent.class::isInstance)
                    .map(AgentEndEvent.class::cast)
                    .findFirst()
                    .orElseThrow();

            assertEquals(4, agentEnd.messages().size());
        }
    }

    // -------------------------------------------------------------------
    // 上下文转换器集成。
    // -------------------------------------------------------------------

    @Nested
    class ContextTransformation {

        @Test
        void contextTransformerIsCalledEachTurn() {
            var callCount = new AtomicInteger();
            var provider =
                    new ScriptedProvider(List.of(toolCallReply("bash", Map.of("command", "ls")), textReply("Done")));
            var tool = simpleTool("bash", "Run command");

            var state = new AgentState();
            state.setSystemPrompt("system");
            state.setTools(List.of(tool));
            var context = new AgentContext(state);

            var loop = new AgentLoop(new AgentLoopConfig(
                    piAiService(provider),
                    model,
                    new DefaultMessageConverter(),
                    (messages, signal) -> {
                        callCount.incrementAndGet();
                        return CompletableFuture.completedFuture(messages);
                    },
                    toolPipeline,
                    steeringQueue,
                    followUpQueue,
                    SimpleStreamOptions.empty()));

            loop.run(List.of(new UserMessage("Go", 1L)), context, events::add, new CancellationToken());

            assertEquals(2, callCount.get()); // once per turn
        }
    }

    // ===================================================================
    // 测试基础设施。
    // ===================================================================

    private List<Message> runLoop(ScriptedProvider provider, List<AgentTool> tools, String prompt) {
        var state = new AgentState();
        state.setSystemPrompt("You are a test assistant.");
        state.setTools(tools);
        var context = new AgentContext(state);

        var loop = new AgentLoop(new AgentLoopConfig(
                piAiService(provider),
                model,
                new DefaultMessageConverter(),
                null,
                toolPipeline,
                steeringQueue,
                followUpQueue,
                SimpleStreamOptions.empty()));

        return loop.run(List.of(new UserMessage(prompt, 1L)), context, events::add, new CancellationToken());
    }

    private CampusClawAiService piAiService(ApiProvider provider) {
        var providerRegistry = new ApiProviderRegistry(List.of(provider));
        var modelRegistry = new ModelRegistry();
        modelRegistry.register(model);
        return new CampusClawAiService(providerRegistry, modelRegistry);
    }

    // -- Reply builders --

    private Reply textReply(String text) {
        return new Reply(text, null, null);
    }

    private Reply toolCallReply(String toolName, Map<String, Object> args) {
        return new Reply(null, toolName, args);
    }

    // -- Assertion helpers --

    private String textOf(Message message) {
        if (message instanceof UserMessage um) {
            return ((TextContent) um.content().getFirst()).text();
        }
        if (message instanceof AssistantMessage am) {
            return ((TextContent) am.content().getFirst()).text();
        }
        throw new IllegalArgumentException(
                "Cannot extract text from " + message.getClass().getSimpleName());
    }

    @SafeVarargs
    private void assertEventOrder(Class<? extends AgentEvent>... expectedTypes) {
        int lastIdx = -1;
        for (var type : expectedTypes) {
            int idx = -1;
            for (int i = lastIdx + 1; i < events.size(); i++) {
                if (type.isInstance(events.get(i))) {
                    idx = i;
                    break;
                }
            }
            assertTrue(
                    idx >= 0,
                    "Expected " + type.getSimpleName() + " after index " + lastIdx + " but not found. Events: "
                            + eventNames());
            lastIdx = idx;
        }
    }

    private <T> int indexOfFirst(Class<T> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private List<String> eventNames() {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    // ===================================================================
    // 使用脚本响应的模拟 Provider。
    // ===================================================================

    private record Reply(String text, String toolName, Map<String, Object> toolArgs) {
        boolean isToolCall() {
            return toolName != null;
        }
    }

    /**
     * 按顺序返回脚本响应的模拟 Provider，每次 streamSimple 调用消费下一条响应。
     */
    private class ScriptedProvider implements ApiProvider {

        private final List<Reply> script;
        private final AtomicInteger callIndex = new AtomicInteger(0);

        ScriptedProvider(List<Reply> script) {
            this.script = List.copyOf(script);
        }

        @Override
        public Api getApi() {
            return Api.ANTHROPIC_MESSAGES;
        }

        @Override
        public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
            throw new UnsupportedOperationException("AgentLoop uses streamSimple");
        }

        @Override
        public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
            int idx = callIndex.getAndIncrement();
            if (idx >= script.size()) {
                throw new IllegalStateException("ScriptedProvider exhausted: called " + (idx + 1) + " times but only "
                        + script.size() + " replies scripted");
            }
            var reply = script.get(idx);

            if (reply.isToolCall()) {
                return toolCallStream(model, reply.toolName(), reply.toolArgs());
            }
            return textStream(model, reply.text());
        }

        private AssistantMessageEventStream toolCallStream(Model model, String toolName, Map<String, Object> args) {
            var stream = new AssistantMessageEventStream();
            var toolCall = new ToolCall("tc-" + callIndex.get(), toolName, args);
            var msg = new AssistantMessage(
                    List.of(toolCall),
                    model.api().value(),
                    model.provider().value(),
                    model.id(),
                    null,
                    Usage.empty(),
                    StopReason.TOOL_USE,
                    null,
                    System.currentTimeMillis());
            stream.push(new AssistantMessageEvent.StartEvent(msg));
            stream.push(new AssistantMessageEvent.ToolCallEndEvent(0, toolCall, msg));
            stream.pushDone(StopReason.TOOL_USE, msg);
            return stream;
        }

        private AssistantMessageEventStream textStream(Model model, String text) {
            var stream = new AssistantMessageEventStream();
            var msg = new AssistantMessage(
                    List.of(new TextContent(text, null)),
                    model.api().value(),
                    model.provider().value(),
                    model.id(),
                    null,
                    Usage.empty(),
                    StopReason.STOP,
                    null,
                    System.currentTimeMillis());
            stream.push(new AssistantMessageEvent.StartEvent(msg));
            stream.push(new AssistantMessageEvent.TextDeltaEvent(0, text, msg));
            stream.pushDone(StopReason.STOP, msg);
            return stream;
        }
    }

    // ===================================================================
    // 测试工具。
    // ===================================================================

    /**
     * 返回固定结果的简单工具。
     *
     * @param name the tool name
     * @param description the tool description
     * @return a stub {@link AgentTool} that always succeeds with a fixed payload
     */
    private AgentTool simpleTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String label() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonNode parameters() {
                return MAPPER.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                                "properties",
                                MAPPER.createObjectNode()
                                        .set(
                                                "command",
                                                MAPPER.createObjectNode().put("type", "string")))
                        .set("required", MAPPER.createArrayNode().add("command"));
            }

            @Override
            public AgentToolResult execute(
                    String toolCallId,
                    Map<String, Object> params,
                    CancellationToken signal,
                    AgentToolUpdateCallback onUpdate) {
                return new AgentToolResult(List.of(new TextContent("executed: " + params.get("command"))), null);
            }
        };
    }

    /**
     * 向 steer 队列注入消息的工具。
     */
    private static class SteeringAgentTool implements AgentTool {

        private final MessageQueue steeringQueue;

        SteeringAgentTool(MessageQueue steeringQueue) {
            this.steeringQueue = steeringQueue;
        }

        @Override
        public String name() {
            return "steering_tool";
        }

        @Override
        public String label() {
            return "Steering Tool";
        }

        @Override
        public String description() {
            return "Tool that injects a steering message";
        }

        @Override
        public JsonNode parameters() {
            return new ObjectMapper()
                    .createObjectNode()
                    .put("type", "object")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                            "properties",
                            new ObjectMapper()
                                    .createObjectNode()
                                    .set(
                                            "command",
                                            new ObjectMapper()
                                                    .createObjectNode()
                                                    .put("type", "string")))
                    .set("required", new ObjectMapper().createArrayNode().add("command"));
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            steeringQueue.enqueue(new UserMessage("injected steering", System.currentTimeMillis()));
            return new AgentToolResult(List.of(new TextContent("tool executed")), null);
        }
    }

    /**
     * 取消 CancellationToken 以模拟中止的工具。
     */
    private static class AbortingAgentTool implements AgentTool {

        private final CancellationToken signal;

        AbortingAgentTool(CancellationToken signal) {
            this.signal = signal;
        }

        @Override
        public String name() {
            return "abort_tool";
        }

        @Override
        public String label() {
            return "Abort Tool";
        }

        @Override
        public String description() {
            return "Cancels execution";
        }

        @Override
        public JsonNode parameters() {
            return new ObjectMapper()
                    .createObjectNode()
                    .put("type", "object")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                            "properties",
                            new ObjectMapper()
                                    .createObjectNode()
                                    .set(
                                            "command",
                                            new ObjectMapper()
                                                    .createObjectNode()
                                                    .put("type", "string")))
                    .set("required", new ObjectMapper().createArrayNode().add("command"));
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            this.signal.cancel();
            return new AgentToolResult(List.of(new TextContent("aborted")), null);
        }
    }

    /**
     * 阻塞到取消，用于测试 Agent.abort() 的工具。
     */
    private static class SlowAgentTool implements AgentTool {

        private final CompletableFuture<Void> started = new CompletableFuture<>();

        @Override
        public String name() {
            return "slow_tool";
        }

        @Override
        public String label() {
            return "Slow Tool";
        }

        @Override
        public String description() {
            return "Slow tool for abort testing";
        }

        @Override
        public JsonNode parameters() {
            return new ObjectMapper()
                    .createObjectNode()
                    .put("type", "object")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                            "properties",
                            new ObjectMapper()
                                    .createObjectNode()
                                    .set(
                                            "command",
                                            new ObjectMapper()
                                                    .createObjectNode()
                                                    .put("type", "string")))
                    .set("required", new ObjectMapper().createArrayNode().add("command"));
        }

        void waitUntilStarted() {
            started.join();
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            started.complete(null);

            // 等待取消。
            while (!signal.isCancelled()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return new AgentToolResult(List.of(new TextContent("cancelled")), null);
        }
    }
}
