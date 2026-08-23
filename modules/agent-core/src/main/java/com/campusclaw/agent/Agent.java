/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import com.campusclaw.agent.context.ContextTransformer;
import com.campusclaw.agent.context.DefaultMessageConverter;
import com.campusclaw.agent.context.MessageConverter;
import com.campusclaw.agent.event.AgentEndEvent;
import com.campusclaw.agent.event.AgentEvent;
import com.campusclaw.agent.event.AgentEventListener;
import com.campusclaw.agent.event.AgentStartEvent;
import com.campusclaw.agent.event.MessageEndEvent;
import com.campusclaw.agent.event.MessageStartEvent;
import com.campusclaw.agent.event.MessageUpdateEvent;
import com.campusclaw.agent.event.ToolExecutionEndEvent;
import com.campusclaw.agent.event.ToolExecutionStartEvent;
import com.campusclaw.agent.loop.AgentLoop;
import com.campusclaw.agent.loop.AgentLoopConfig;
import com.campusclaw.agent.queue.MessageQueue;
import com.campusclaw.agent.queue.MessageQueue.DeliveryMode;
import com.campusclaw.agent.state.AgentState;
import com.campusclaw.agent.tool.AfterToolCallHandler;
import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.BeforeToolCallHandler;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.UserMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置并运行 Agent Runtime 的统一门面。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private static final Executor VIRTUAL_THREAD_EXECUTOR =
            command -> Thread.ofVirtual().start(command);

    private final CampusClawAiService piAiService;
    private final AgentState state;
    private final MessageConverter messageConverter;
    private final ContextTransformer contextTransformer;
    private final ToolExecutionPipeline toolPipeline;
    private final MessageQueue steeringQueue;
    private final MessageQueue followUpQueue;
    private final SimpleStreamOptions baseStreamOptions;
    private final CopyOnWriteArrayList<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Object executionLock = new Object();

    private volatile CompletableFuture<Void> currentExecution = CompletableFuture.completedFuture(null);
    private volatile CancellationToken currentSignal;

    public Agent(CampusClawAiService piAiService) {
        this(
                piAiService,
                new AgentState(),
                new DefaultMessageConverter(),
                null,
                new ToolExecutionPipeline(),
                new MessageQueue(),
                new MessageQueue(),
                SimpleStreamOptions.empty());
    }

    Agent(
            CampusClawAiService piAiService,
            AgentState state,
            MessageConverter messageConverter,
            ContextTransformer contextTransformer,
            ToolExecutionPipeline toolPipeline,
            MessageQueue steeringQueue,
            MessageQueue followUpQueue,
            SimpleStreamOptions baseStreamOptions) {
        this.piAiService = Objects.requireNonNull(piAiService, "piAiService");
        this.state = Objects.requireNonNull(state, "state");
        this.messageConverter = messageConverter != null ? messageConverter : new DefaultMessageConverter();
        this.contextTransformer = contextTransformer;
        this.toolPipeline = toolPipeline != null ? toolPipeline : new ToolExecutionPipeline();
        this.steeringQueue = steeringQueue != null ? steeringQueue : new MessageQueue();
        this.followUpQueue = followUpQueue != null ? followUpQueue : new MessageQueue();
        this.baseStreamOptions = baseStreamOptions != null ? baseStreamOptions : SimpleStreamOptions.empty();
    }

    public AgentState getState() {
        return state;
    }

    public void setSystemPrompt(String prompt) {
        state.setSystemPrompt(prompt);
    }

    public void setModel(Model model) {
        state.setModel(model);
    }

    public void setThinkingLevel(ThinkingLevel level) {
        state.setThinkingLevel(level);
    }

    public void setTools(List<AgentTool> tools) {
        state.setTools(tools);
    }

    public void replaceMessages(List<Message> messages) {
        state.replaceMessages(messages);
    }

    public void appendMessage(Message message) {
        state.appendMessage(message);
    }

    public void clearMessages() {
        state.clearMessages();
    }

    public void reset() {
        state.setSystemPrompt(null);
        state.setModel(null);
        state.setThinkingLevel(ThinkingLevel.OFF);
        state.setTools(List.of());
        state.clearMessages();
        state.setStreaming(false);
        state.setStreamMessage(null);
        state.clearPendingToolCalls();
        state.setError(null);
        clearSteeringQueue();
        clearFollowUpQueue();
    }

    public void setBeforeToolCall(BeforeToolCallHandler handler) {
        toolPipeline.setBeforeToolCall(handler);
    }

    public void setAfterToolCall(AfterToolCallHandler handler) {
        toolPipeline.setAfterToolCall(handler);
    }

    public void steer(Message message) {
        steeringQueue.enqueue(message);
    }

    public void followUp(Message message) {
        followUpQueue.enqueue(message);
    }

    public void setSteeringMode(DeliveryMode mode) {
        steeringQueue.setMode(mode);
    }

    public void setFollowUpMode(DeliveryMode mode) {
        followUpQueue.setMode(mode);
    }

    public boolean hasQueuedControlMessages() {
        return steeringQueue.hasMessages() || followUpQueue.hasMessages();
    }

    public void clearSteeringQueue() {
        steeringQueue.clear();
    }

    public void clearFollowUpQueue() {
        followUpQueue.clear();
    }

    public CompletableFuture<Void> prompt(String message) {
        Objects.requireNonNull(message, "message");
        return prompt(new UserMessage(message, System.currentTimeMillis()));
    }

    public CompletableFuture<Void> prompt(Message message) {
        Objects.requireNonNull(message, "message");
        return startExecution(List.of(message), false);
    }

    public CompletableFuture<Void> continueExecution() {
        return startExecution(List.of(), true);
    }

    public CompletableFuture<Void> continueQueuedExecution() {
        List<Message> messages = steeringQueue.drain();
        if (messages.isEmpty()) {
            messages = followUpQueue.drain();
        }
        if (messages.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No queued control message"));
        }
        return startExecution(messages, false);
    }

    public void abort() {
        var signal = currentSignal;
        if (signal != null) {
            signal.cancel();
        }
    }

    public CompletableFuture<Void> waitForIdle() {
        CompletableFuture<Void> execution;
        synchronized (executionLock) {
            execution = currentExecution;
        }
        return execution.handle((unused, throwable) -> null);
    }

    public Runnable subscribe(AgentEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private CompletableFuture<Void> startExecution(List<Message> messages, boolean continueOnly) {
        synchronized (executionLock) {
            if (!currentExecution.isDone()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Agent is already running"));
            }

            var model = state.getModel();
            if (model == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Model is not set"));
            }

            state.setError(null);
            var signal = new CancellationToken();
            currentSignal = signal;

            var context = new AgentContext(state);
            var loop = new AgentLoop(new AgentLoopConfig(
                    piAiService,
                    model,
                    messageConverter,
                    contextTransformer,
                    toolPipeline,
                    steeringQueue,
                    followUpQueue,
                    buildStreamOptions()));

            var execution = CompletableFuture.runAsync(
                            () -> {
                                if (continueOnly) {
                                    loop.continueLoop(context, this::emit, signal);
                                } else {
                                    loop.run(messages, context, this::emit, signal);
                                }
                            },
                            VIRTUAL_THREAD_EXECUTOR)
                    .whenComplete((unused, throwable) -> {
                        state.setStreaming(false);
                        state.setStreamMessage(null);
                        state.clearPendingToolCalls();
                        synchronized (executionLock) {
                            currentSignal = null;
                        }
                        if (throwable != null) {
                            state.setError(formatError(throwable));
                        }
                    });

            currentExecution = execution;
            return execution;
        }
    }

    private SimpleStreamOptions buildStreamOptions() {
        return baseStreamOptions.toBuilder().reasoning(state.getThinkingLevel()).build();
    }

    private void emit(AgentEvent event) {
        applyEventToState(event);
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                // 监听器异常不得中断 Agent 执行，仅记录日志以便定位。
                log.warn(
                        "agent event listener threw exception (event={})",
                        event.getClass().getSimpleName(),
                        e);
            }
        }
    }

    private void applyEventToState(AgentEvent event) {
        switch (event) {
            case AgentStartEvent ignored -> {
                state.setStreaming(false);
                state.setStreamMessage(null);
                state.clearPendingToolCalls();
            }
            case AgentEndEvent e -> {
                state.setStreaming(false);
                state.setStreamMessage(null);
                state.replaceMessages(e.messages());
            }
            case MessageStartEvent e -> {
                if (e.message() instanceof AssistantMessage assistantMessage) {
                    state.setStreaming(true);
                    state.setStreamMessage(assistantMessage);
                }
            }
            case MessageUpdateEvent e -> {
                state.setStreaming(true);
                state.setStreamMessage(e.message());
            }
            case MessageEndEvent e -> {
                if (e.message() instanceof AssistantMessage) {
                    state.setStreaming(false);
                    state.setStreamMessage(null);
                }
            }
            case ToolExecutionStartEvent e -> state.addPendingToolCall(e.toolCallId());
            case ToolExecutionEndEvent e -> state.removePendingToolCall(e.toolCallId());
            default -> {}
        }
    }

    public static String formatError(Throwable throwable) {
        var current = throwable;

        // 解包常见的异步包装异常。
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }

        // 拼接原因链，避免 SDK 的外层异常掩盖真实错误。
        String message = current.getMessage() != null
                ? current.getMessage()
                : current.getClass().getSimpleName();
        var cause = current.getCause();
        if (cause != null && cause != current) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null && !causeMsg.isBlank() && !message.contains(causeMsg)) {
                message = message + ": " + causeMsg;
            }

            // 再读取一层，以覆盖 SSLHandshakeException 到 PKIX 等深层原因。
            var rootCause = cause.getCause();
            if (rootCause != null && rootCause != cause) {
                String rootMsg = rootCause.getMessage();
                if (rootMsg != null && !rootMsg.isBlank() && !message.contains(rootMsg)) {
                    message = message + ": " + rootMsg;
                }
            }
        }

        // 连接失败时追加代理配置提示。
        if (isConnectionError(current)) {
            message += "\n  提示: 请检查服务出站代理配置和网络连通性";
        }
        return message;
    }

    private static boolean isConnectionError(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.SocketTimeoutException
                    || (c.getMessage() != null && c.getMessage().contains("timed out"))) {
                return true;
            }
        }
        return false;
    }
}
