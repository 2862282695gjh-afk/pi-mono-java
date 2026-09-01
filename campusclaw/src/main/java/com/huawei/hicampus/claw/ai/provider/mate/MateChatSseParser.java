/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.provider.mate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.huawei.hicampus.claw.ai.model.ModelRegistry;
import com.huawei.hicampus.claw.ai.stream.AssistantMessageEvent;
import com.huawei.hicampus.claw.ai.stream.AssistantMessageEventStream;
import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ThinkingContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * 将原始 OpenAI Chat SSE 事件投影为统一 AssistantMessageEvent。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
final class MateChatSseParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(MateChatSseParser.class);

    private static final List<String> REASONING_FIELDS =
            List.of("reasoning_content", "reasoning", "reasoning_text", "reasoning_details");

    private final ObjectMapper mapper;

    private final Model model;

    private final AssistantMessageEventStream stream;

    private final List<ContentBlock> content = new ArrayList<>();

    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();

    private final AtomicBoolean terminal = new AtomicBoolean();

    private Usage usage = Usage.empty();

    private StopReason stopReason = StopReason.STOP;

    private MateInvocationErrorCode terminalErrorCode;

    private String responseId;

    private String responseModel;

    private int textIndex = -1;

    private int thinkingIndex = -1;

    private String reasoningField;

    private final StringBuilder reasoningText = new StringBuilder();

    private JsonNode reasoningDetails;

    private boolean started;

    MateChatSseParser(ObjectMapper mapper, Model model, AssistantMessageEventStream stream) {
        this.mapper = mapper;
        this.model = model;
        this.stream = stream;
    }

    void accept(ServerSentEvent<String> event) {
        if (terminal.get()) {
            return;
        }
        if ("error".equals(event.event())) {
            fail(streamError(event.data()));
            return;
        }
        String data = event.data();
        if (data == null || data.isBlank()) {
            return;
        }
        if ("[DONE]".equals(data)) {
            finish();
            return;
        }
        parseChunk(data);
    }

    void completeWithoutDone() {
        if (!terminal.get()) {
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.UPSTREAM_STREAM_ERROR;
            recordFailure("mate.response.sse.complete", errorCode, null);
            fail(new MateModelInvocationException(errorCode));
        }
    }

    void fail(Throwable error) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        MateInvocationErrorCode errorCode = classifyFailure(error);
        AssistantMessage message = message(StopReason.ERROR, errorCode);
        stream.pushError("error", message);
    }

    void abort() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        stream.pushError("aborted", message(StopReason.ABORTED, null));
    }

    private void parseChunk(String data) {
        try {
            JsonNode chunk = mapper.readTree(data);
            updateIdentity(chunk);
            emitStartIfNeeded();
            parseUsage(chunk.path("usage"));
            if (!chunk.path("choices").isEmpty()) {
                parseChoice(chunk.path("choices").get(0));
            }
        } catch (MateModelInvocationException error) {
            fail(error);
        } catch (Exception error) {
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.INVALID_CHAT_SSE;
            recordFailure("mate.response.sse.parse", errorCode, error);
            fail(new MateModelInvocationException(errorCode));
        }
    }

    private void updateIdentity(JsonNode chunk) {
        if (chunk.hasNonNull("id")) {
            responseId = chunk.path("id").asText();
        }
        if (chunk.hasNonNull("model")) {
            responseModel = chunk.path("model").asText();
        }
    }

    private void emitStartIfNeeded() {
        if (!started) {
            started = true;
            stream.push(new AssistantMessageEvent.StartEvent(message(StopReason.STOP, null)));
        }
    }

    private void parseChoice(JsonNode choice) {
        if (choice.hasNonNull("finish_reason")) {
            stopReason = mapStopReason(choice.path("finish_reason").asText());
        }
        JsonNode delta = choice.path("delta");
        parseReasoning(delta);
        if (delta.hasNonNull("content")) {
            appendText(delta.path("content").asText());
        }
        if (delta.path("tool_calls").isArray()) {
            delta.path("tool_calls").forEach(this::appendToolCall);
        }
    }

    private void parseReasoning(JsonNode delta) {
        for (String field : REASONING_FIELDS) {
            JsonNode value = delta.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            requireConsistentReasoningField(field);
            if ("reasoning_details".equals(field)) {
                reasoningDetails = value.deepCopy();
                startThinkingIfNeeded();
            } else {
                appendReasoningText(field, value.asText());
            }
        }
    }

    private void requireConsistentReasoningField(String field) {
        if (reasoningField == null) {
            reasoningField = field;
        } else if (!reasoningField.equals(field)) {
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.INVALID_CHAT_SSE;
            recordFailure("mate.response.reasoning.validate", errorCode, null);
            throw new MateModelInvocationException(errorCode);
        }
    }

    private void appendReasoningText(String field, String delta) {
        if (delta.isEmpty()) {
            return;
        }
        startThinkingIfNeeded();
        reasoningText.append(delta);
        updateThinkingBlock();
        stream.push(new AssistantMessageEvent.ThinkingDeltaEvent(thinkingIndex, delta, partial()));
    }

    private void startThinkingIfNeeded() {
        if (thinkingIndex >= 0) {
            return;
        }
        thinkingIndex = content.size();
        content.add(new ThinkingContent("", reasoningSignature(), false));
        stream.push(new AssistantMessageEvent.ThinkingStartEvent(thinkingIndex, partial()));
    }

    private void updateThinkingBlock() {
        content.set(thinkingIndex, new ThinkingContent(reasoningText.toString(), reasoningSignature(), false));
    }

    private String reasoningSignature() {
        JsonNode value = "reasoning_details".equals(reasoningField)
                ? reasoningDetails
                : mapper.getNodeFactory().textNode(reasoningText.toString());
        if (reasoningField == null || value == null) {
            return null;
        }
        return MateReasoningSignature.encode(mapper, reasoningField, value);
    }

    private void appendText(String delta) {
        if (delta.isEmpty()) {
            return;
        }
        if (textIndex < 0) {
            textIndex = content.size();
            content.add(new TextContent(""));
            stream.push(new AssistantMessageEvent.TextStartEvent(textIndex, partial()));
        }
        TextContent existing = (TextContent) content.get(textIndex);
        content.set(textIndex, new TextContent(existing.text() + delta));
        stream.push(new AssistantMessageEvent.TextDeltaEvent(textIndex, delta, partial()));
    }

    private void appendToolCall(JsonNode delta) {
        int index = delta.path("index").asInt();
        ToolAccumulator tool = tools.computeIfAbsent(index, ignored -> startTool());
        if (delta.hasNonNull("id")) {
            tool.id = delta.path("id").asText();
        }
        JsonNode function = delta.path("function");
        if (function.hasNonNull("name")) {
            tool.name = function.path("name").asText();
        }
        if (function.hasNonNull("arguments")) {
            String arguments = function.path("arguments").asText();
            tool.arguments.append(arguments);
            stream.push(new AssistantMessageEvent.ToolCallDeltaEvent(tool.contentIndex, arguments, partial()));
        }
    }

    private ToolAccumulator startTool() {
        ToolAccumulator tool = new ToolAccumulator();
        tool.contentIndex = content.size();
        content.add(new ToolCall("", "", Map.of()));
        stream.push(new AssistantMessageEvent.ToolCallStartEvent(tool.contentIndex, partial()));
        return tool;
    }

    private void parseUsage(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        int cached = value.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        int input = Math.max(0, value.path("prompt_tokens").asInt(0) - cached);
        int output = value.path("completion_tokens").asInt(0);
        int total = value.path("total_tokens").asInt(input + output + cached);
        Usage tokens = new Usage(input, output, cached, 0, total, Cost.empty());
        usage = new Usage(input, output, cached, 0, total, ModelRegistry.calculateCost(model, tokens));
    }

    private void finish() {
        if (terminal.get()) {
            return;
        }
        try {
            finishThinking();
            finishText();
            finishTools();
        } catch (RuntimeException error) {
            fail(normalizeSseFailure("mate.response.finish", error));
            return;
        }
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        if (stopReason == StopReason.ERROR) {
            terminalErrorCode = MateInvocationErrorCode.MODEL_CONTENT_FILTERED;
            recordFailure("mate.response.finish", terminalErrorCode, null);
        }
        stream.pushDone(stopReason, message(stopReason, terminalErrorCode));
    }

    private void finishThinking() {
        if (thinkingIndex < 0) {
            return;
        }
        updateThinkingBlock();
        stream.push(new AssistantMessageEvent.ThinkingEndEvent(thinkingIndex, reasoningText.toString(), partial()));
    }

    private void finishText() {
        if (textIndex < 0) {
            return;
        }
        String text = ((TextContent) content.get(textIndex)).text();
        stream.push(new AssistantMessageEvent.TextEndEvent(textIndex, text, partial()));
    }

    private void finishTools() {
        for (ToolAccumulator tool : tools.values()) {
            ToolCall call = new ToolCall(tool.id, tool.name, parseArguments(tool.arguments.toString()));
            content.set(tool.contentIndex, call);
            stream.push(new AssistantMessageEvent.ToolCallEndEvent(tool.contentIndex, call, partial()));
        }
    }

    private Map<String, Object> parseArguments(String value) {
        try {
            if (value.isBlank()) {
                return Map.of();
            }
            return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.INVALID_CHAT_SSE;
            recordFailure("mate.response.toolArguments.parse", errorCode, error);
            throw new MateModelInvocationException(errorCode);
        }
    }

    private AssistantMessage partial() {
        return message(StopReason.STOP, null);
    }

    private AssistantMessage message(StopReason reason, MateInvocationErrorCode errorCode) {
        return new AssistantMessage(
                List.copyOf(content),
                Api.OPENAI_COMPLETIONS.value(),
                model.provider().value(),
                model.id(),
                responseId,
                responseModel,
                usage,
                reason,
                errorCode == null ? null : errorCode.name(),
                null,
                System.currentTimeMillis());
    }

    private static StopReason mapStopReason(String value) {
        return switch (value) {
            case "length" -> StopReason.LENGTH;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }

    private MateModelInvocationException streamError(String data) {
        if (data == null || data.isBlank()) {
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.UPSTREAM_STREAM_ERROR;
            recordFailure("mate.response.sse.error", errorCode, null);
            return new MateModelInvocationException(errorCode);
        }
        try {
            String upstreamCode = mapper.readTree(data).path("resCode").asText("UPSTREAM_STREAM_ERROR");
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.fromUpstream(upstreamCode);
            recordFailure("mate.response.sse.error", errorCode, null);
            return new MateModelInvocationException(errorCode);
        } catch (Exception error) {
            MateInvocationErrorCode errorCode = MateInvocationErrorCode.UPSTREAM_STREAM_ERROR;
            recordFailure("mate.response.sse.error", errorCode, error);
            return new MateModelInvocationException(errorCode);
        }
    }

    private MateInvocationErrorCode classifyFailure(Throwable error) {
        if (error instanceof MateModelInvocationException invocation) {
            return invocation.errorCode();
        }
        MateInvocationErrorCode errorCode = transportErrorCode(error);
        recordFailure("mate.response.stream", errorCode, error);
        return errorCode;
    }

    private MateModelInvocationException normalizeSseFailure(String operation, RuntimeException error) {
        if (error instanceof MateModelInvocationException invocation) {
            return invocation;
        }
        MateInvocationErrorCode errorCode = MateInvocationErrorCode.INVALID_CHAT_SSE;
        recordFailure(operation, errorCode, error);
        return new MateModelInvocationException(errorCode);
    }

    private void recordFailure(String operation, MateInvocationErrorCode errorCode, Throwable error) {
        LoggingEventBuilder event = errorCode.warning() ? LOGGER.atWarn() : LOGGER.atError();
        event.addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", operation)
                .addKeyValue("errorCode", errorCode.name())
                .addKeyValue("modelId", model.id());
        if (error != null) {
            event.setCause(error);
        }
        event.log("CampusClaw failure: operation={}, errorCode={}", operation, errorCode.name());
    }

    private static MateInvocationErrorCode transportErrorCode(Throwable error) {
        boolean requestFailure = false;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return MateInvocationErrorCode.MODEL_INVOCATION_TIMEOUT;
            }
            if (current instanceof ConnectException) {
                return MateInvocationErrorCode.MANAGER_UNAVAILABLE;
            }
            requestFailure |= current instanceof WebClientRequestException;
        }
        return requestFailure
                ? MateInvocationErrorCode.MANAGER_UNAVAILABLE
                : MateInvocationErrorCode.UPSTREAM_STREAM_ERROR;
    }

    private static final class ToolAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
        private int contentIndex;
    }
}
