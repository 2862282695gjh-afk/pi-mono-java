/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import com.huawei.hicampus.claw.agent.context.ContextTransformer;
import com.huawei.hicampus.claw.agent.context.MessageConverter;
import com.huawei.hicampus.claw.agent.event.AgentEndEvent;
import com.huawei.hicampus.claw.agent.event.AgentEventListener;
import com.huawei.hicampus.claw.agent.event.AgentStartEvent;
import com.huawei.hicampus.claw.agent.event.MessageEndEvent;
import com.huawei.hicampus.claw.agent.event.MessageStartEvent;
import com.huawei.hicampus.claw.agent.event.MessageUpdateEvent;
import com.huawei.hicampus.claw.agent.event.TurnEndEvent;
import com.huawei.hicampus.claw.agent.event.TurnStartEvent;
import com.huawei.hicampus.claw.agent.queue.MessageQueue;
import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolCallWithTool;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.stream.AssistantMessageEvent;
import com.huawei.hicampus.claw.ai.stream.AssistantMessageEventStream;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.Context;
import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.SimpleStreamOptions;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.Tool;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.ToolResultMessage;

import reactor.core.publisher.Sinks;

/**
 * 流式接收模型响应、执行工具并管理多轮续跑的 Agent 核心循环。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class AgentLoop {

    private final StreamFunction streamFunction;
    private final Model model;
    private final MessageConverter convertToLlm;
    private final ContextTransformer transformContext;
    private final ToolExecutionPipeline toolPipeline;
    private final MessageQueue steeringQueue;
    private final MessageQueue followUpQueue;
    private final SimpleStreamOptions streamOptions;
    private final SteeringMessageSupplier getSteeringMessages;
    private final SteeringMessageSupplier getFollowUpMessages;

    public AgentLoop(AgentLoopConfig config) {
        Objects.requireNonNull(config, "config");
        this.streamFunction = config.effectiveStreamFunction();
        this.model = config.model();
        this.convertToLlm = config.convertToLlm();
        this.transformContext = config.transformContext();
        this.toolPipeline = config.toolPipeline();
        this.steeringQueue = config.steeringQueue();
        this.followUpQueue = config.followUpQueue();
        this.streamOptions = config.streamOptions();
        this.getSteeringMessages = config.getSteeringMessages();
        this.getFollowUpMessages = config.getFollowUpMessages();
    }

    public List<Message> run(
            List<Message> prompts, AgentContext context, AgentEventListener listener, CancellationToken signal) {
        return runInternal(prompts != null ? prompts : List.of(), context, listener, signal);
    }

    public List<Message> continueLoop(AgentContext context, AgentEventListener listener, CancellationToken signal) {
        return runInternal(List.of(), context, listener, signal);
    }

    private List<Message> runInternal(
            List<Message> prompts, AgentContext context, AgentEventListener listener, CancellationToken signal) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        AgentEventListener eventListener = listener != null ? listener : event -> {};
        if (!prompts.isEmpty()) {
            context.appendMessages(prompts);
        }
        List<Message> pendingTurnInputs = List.copyOf(prompts);
        eventListener.onEvent(new AgentStartEvent());
        try {
            while (!signal.isCancelled()) {
                eventListener.onEvent(new TurnStartEvent());
                emitPendingInputs(pendingTurnInputs, eventListener);
                AssistantMessage assistantMessage = invokeModel(context, eventListener, signal);
                context.appendMessage(assistantMessage);
                context.setAssistantMessage(assistantMessage);
                eventListener.onEvent(new MessageEndEvent(assistantMessage));
                if (assistantMessage.stopReason() == StopReason.ERROR
                        || assistantMessage.stopReason() == StopReason.ABORTED) {
                    eventListener.onEvent(new TurnEndEvent(assistantMessage, List.of()));
                    break;
                }
                var toolCalls = extractToolCalls(assistantMessage);
                if (!toolCalls.isEmpty()) {
                    pendingTurnInputs = runToolPhase(context, signal, eventListener, assistantMessage, toolCalls);
                    continue;
                }
                var controlMessages = drainNextControlMessages();
                eventListener.onEvent(new TurnEndEvent(assistantMessage, List.of()));
                if (controlMessages.isEmpty()) {
                    break;
                }
                context.appendMessages(controlMessages);
                pendingTurnInputs = controlMessages;
            }
            return context.messages();
        } catch (CancellationException error) {
            if (!signal.isCancelled()) {
                throw error;
            }
            return context.messages();
        } finally {
            eventListener.onEvent(new AgentEndEvent(context.messages()));
        }
    }

    private List<Message> runToolPhase(
            AgentContext context,
            CancellationToken signal,
            AgentEventListener eventListener,
            AssistantMessage assistantMessage,
            List<ToolCall> toolCalls) {
        ResolvedToolCalls resolution = resolveToolCallsSafe(toolCalls, context.tools());
        List<ToolResultMessage> knownResults = List.of();
        if (!resolution.known().isEmpty()) {
            knownResults = toolPipeline.executeAll(resolution.known(), context, signal, eventListener);
        }
        List<ToolResultMessage> toolResults = resolution.merge(knownResults);
        context.appendMessages(new ArrayList<>(toolResults));
        var steeringMessages = drainSteeringMessages();
        if (!steeringMessages.isEmpty()) {
            context.appendMessages(steeringMessages);
        }
        eventListener.onEvent(new TurnEndEvent(assistantMessage, toolResults));
        return steeringMessages;
    }

    private void emitPendingInputs(List<Message> pendingTurnInputs, AgentEventListener listener) {
        for (var message : pendingTurnInputs) {
            listener.onEvent(new MessageStartEvent(message));
            listener.onEvent(new MessageEndEvent(message));
        }
    }

    /**
     * 保存 LLM 事件流结束或取消时的消费结果。
     */
    private record StreamConsumeResult(AssistantMessage message, boolean assistantStarted) {}

    private AssistantMessage invokeModel(AgentContext context, AgentEventListener listener, CancellationToken signal) {
        var transformedMessages = transformMessages(context.messages(), signal);
        var llmMessages = convertToLlm.convert(transformedMessages);
        var llmContext = new Context(context.systemPrompt(), llmMessages, toLlmTools(context.tools()));
        var stream = streamFunction.stream(model, llmContext, streamOptions);
        var cancelSink = Sinks.<Object>one();
        signal.onCancel(() -> cancelSink.tryEmitEmpty());
        var result = consumeStream(stream, cancelSink, listener, signal);
        if (signal.isCancelled()) {
            return synthesizeAbortedMessage(result, listener);
        }
        var assistantMessage = result.message();
        if (assistantMessage == null) {
            assistantMessage = stream.result().block();
            if (assistantMessage != null && !result.assistantStarted()) {
                listener.onEvent(new MessageStartEvent(assistantMessage));
            }
        }
        if (assistantMessage == null) {
            throw new IllegalStateException("LLM stream completed without producing an assistant message");
        }
        return assistantMessage;
    }

    private StreamConsumeResult consumeStream(
            AssistantMessageEventStream stream,
            Sinks.One<Object> cancelSink,
            AgentEventListener listener,
            CancellationToken signal) {
        AssistantMessage assistantMessage = null;
        var assistantStarted = false;
        for (var event : stream.asFlux().takeUntilOther(cancelSink.asMono()).toIterable()) {
            if (signal.isCancelled()) {
                break;
            }
            var currentMessage = extractAssistantMessage(event);
            if (!assistantStarted) {
                listener.onEvent(new MessageStartEvent(currentMessage));
                assistantStarted = true;
            }
            if (!(event instanceof AssistantMessageEvent.StartEvent)) {
                listener.onEvent(new MessageUpdateEvent(currentMessage, event));
            }
            assistantMessage = currentMessage;
        }
        return new StreamConsumeResult(assistantMessage, assistantStarted);
    }

    // 合成 ABORTED 消息，使外层循环直接结束，避免在已断开的流上阻塞等待结果。
    private AssistantMessage synthesizeAbortedMessage(StreamConsumeResult result, AgentEventListener listener) {
        var msg = result.message();
        var aborted = new AssistantMessage(
                msg != null ? msg.content() : List.of(),
                msg != null ? msg.api() : model.api().value(),
                msg != null ? msg.provider() : model.provider().value(),
                msg != null ? msg.model() : model.id(),
                msg != null ? msg.responseId() : null,
                msg != null ? msg.usage() : null,
                StopReason.ABORTED,
                null,
                System.currentTimeMillis());
        if (!result.assistantStarted()) {
            listener.onEvent(new MessageStartEvent(aborted));
        }
        return aborted;
    }

    private List<Message> transformMessages(List<Message> messages, CancellationToken signal) {
        if (transformContext == null) {
            return messages;
        }

        try {
            return transformContext.transform(messages, signal).join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to transform context", e);
        }
    }

    private List<Tool> toLlmTools(List<AgentTool> tools) {
        if (tools.isEmpty()) {
            return List.of();
        }

        return tools.stream()
                .map(tool -> new Tool(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    private List<ToolCall> extractToolCalls(AssistantMessage assistantMessage) {
        var toolCalls = new ArrayList<ToolCall>();
        for (var block : assistantMessage.content()) {
            if (block instanceof ToolCall toolCall) {
                toolCalls.add(toolCall);
            }
        }
        return List.copyOf(toolCalls);
    }

    private ResolvedToolCalls resolveToolCallsSafe(List<ToolCall> toolCalls, List<AgentTool> tools) {
        var toolsByName = new LinkedHashMap<String, AgentTool>();
        for (var tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
        var known = new ArrayList<ToolCallWithTool>();
        var knownPositions = new ArrayList<Integer>();
        var unknown = new ArrayList<ToolResultMessage>();
        var unknownPositions = new ArrayList<Integer>();
        for (int index = 0; index < toolCalls.size(); index++) {
            ToolCall toolCall = toolCalls.get(index);
            var tool = toolsByName.get(toolCall.name());
            if (tool != null) {
                known.add(new ToolCallWithTool(toolCall, tool, toolCall.arguments()));
                knownPositions.add(index);
            } else {
                unknown.add(new ToolResultMessage(
                        toolCall.id(),
                        toolCall.name(),
                        List.of(new TextContent("Tool " + toolCall.name() + " not found", null)),
                        null,
                        true,
                        System.currentTimeMillis()));
                unknownPositions.add(index);
            }
        }
        return new ResolvedToolCalls(
                List.copyOf(known),
                List.copyOf(knownPositions),
                List.copyOf(unknown),
                List.copyOf(unknownPositions),
                toolCalls.size());
    }

    private record ResolvedToolCalls(
            List<ToolCallWithTool> known,
            List<Integer> knownPositions,
            List<ToolResultMessage> unknown,
            List<Integer> unknownPositions,
            int size) {

        private List<ToolResultMessage> merge(List<ToolResultMessage> knownResults) {
            var ordered = new ArrayList<ToolResultMessage>(java.util.Collections.nCopies(size, null));
            for (int index = 0; index < knownResults.size(); index++) {
                ordered.set(knownPositions.get(index), knownResults.get(index));
            }
            for (int index = 0; index < unknown.size(); index++) {
                ordered.set(unknownPositions.get(index), unknown.get(index));
            }
            return List.copyOf(ordered);
        }
    }

    private List<Message> drainSteeringMessages() {
        if (getSteeringMessages != null) {
            var msgs = getSteeringMessages.get();
            if (msgs != null && !msgs.isEmpty()) {
                return msgs;
            }
        }
        return steeringQueue.drain();
    }

    private List<Message> drainFollowUpMessages() {
        if (getFollowUpMessages != null) {
            var msgs = getFollowUpMessages.get();
            if (msgs != null && !msgs.isEmpty()) {
                return msgs;
            }
        }
        return followUpQueue.drain();
    }

    private List<Message> drainNextControlMessages() {
        List<Message> steeringMessages = drainSteeringMessages();
        return steeringMessages.isEmpty() ? drainFollowUpMessages() : steeringMessages;
    }

    private AssistantMessage extractAssistantMessage(AssistantMessageEvent event) {
        return switch (event) {
            case AssistantMessageEvent.StartEvent e -> e.partial();
            case AssistantMessageEvent.TextStartEvent e -> e.partial();
            case AssistantMessageEvent.TextDeltaEvent e -> e.partial();
            case AssistantMessageEvent.TextEndEvent e -> e.partial();
            case AssistantMessageEvent.ThinkingStartEvent e -> e.partial();
            case AssistantMessageEvent.ThinkingDeltaEvent e -> e.partial();
            case AssistantMessageEvent.ThinkingEndEvent e -> e.partial();
            case AssistantMessageEvent.ToolCallStartEvent e -> e.partial();
            case AssistantMessageEvent.ToolCallDeltaEvent e -> e.partial();
            case AssistantMessageEvent.ToolCallEndEvent e -> e.partial();
            case AssistantMessageEvent.DoneEvent e -> e.message();
            case AssistantMessageEvent.ErrorEvent e -> e.error();
        };
    }
}
