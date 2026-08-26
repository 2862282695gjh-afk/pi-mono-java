/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider.mate;

import java.util.List;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.ImageContent;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.Tool;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.UserMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 将 AgentLoop Context 转换为 Mate Chat JSON 子集。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
final class MateChatRequestMapper {
    private final ObjectMapper mapper;

    MateChatRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode map(Model model, Context context, SimpleStreamOptions options) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model.id());
        ArrayNode messages = request.putArray("messages");
        appendSystem(messages, context.systemPrompt());
        for (Message message : context.messages()) {
            appendMessage(messages, message);
        }
        appendTools(request, context.tools());
        appendOptions(request, options);
        request.put("stream", true);
        request.putObject("stream_options").put("include_usage", true);
        return request;
    }

    private static void appendSystem(ArrayNode messages, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
    }

    private void appendMessage(ArrayNode messages, Message message) {
        switch (message) {
            case UserMessage user -> appendUser(messages, user);
            case AssistantMessage assistant -> appendAssistant(messages, assistant);
            case ToolResultMessage result -> appendToolResult(messages, result);
        }
    }

    private static void appendUser(ArrayNode messages, UserMessage message) {
        messages.addObject().put("role", "user").put("content", textOnly(message.content(), "user"));
    }

    private void appendAssistant(ArrayNode messages, AssistantMessage message) {
        ObjectNode output = messages.addObject();
        output.put("role", "assistant");
        String text = textOnlyAssistant(message.content());
        List<ToolCall> toolCalls = message.content().stream()
                .filter(ToolCall.class::isInstance)
                .map(ToolCall.class::cast)
                .toList();
        if (text.isEmpty() && !toolCalls.isEmpty()) {
            output.putNull("content");
        } else {
            output.put("content", text);
        }
        appendReasoning(output, message.content());
        appendToolCalls(output, toolCalls);
    }

    private void appendReasoning(ObjectNode output, List<ContentBlock> content) {
        for (ContentBlock block : content) {
            if (block instanceof ThinkingContent thinking && thinking.thinkingSignature() != null) {
                var field = MateReasoningSignature.decode(mapper, thinking.thinkingSignature());
                output.set(field.field(), field.value());
            }
        }
    }

    private void appendToolCalls(ObjectNode output, List<ToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            return;
        }
        ArrayNode calls = output.putArray("tool_calls");
        for (ToolCall call : toolCalls) {
            ObjectNode item = calls.addObject();
            item.put("id", call.id());
            item.put("type", "function");
            item.putObject("function").put("name", call.name()).put("arguments", serializeArguments(call));
        }
    }

    private void appendToolResult(ArrayNode messages, ToolResultMessage message) {
        ObjectNode output = messages.addObject();
        output.put("role", "tool");
        output.put("tool_call_id", message.toolCallId());
        output.put("content", textOnly(message.content(), "tool result"));
    }

    private void appendTools(ObjectNode request, List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        ArrayNode output = request.putArray("tools");
        for (Tool tool : tools) {
            if (tool.parameters() == null || !tool.parameters().isObject()) {
                throw unsupported("Tool parameters must be a JSON object");
            }
            ObjectNode function = output.addObject().put("type", "function").putObject("function");
            function.put("name", tool.name());
            if (tool.description() != null) {
                function.put("description", tool.description());
            }
            function.set("parameters", tool.parameters());
        }
    }

    private static void appendOptions(ObjectNode request, SimpleStreamOptions options) {
        if (options == null) {
            return;
        }
        if (options.toolChoice() != null) {
            request.put("tool_choice", options.toolChoice().value());
        }
        if (options.maxTokens() != null) {
            request.put("max_output_tokens", options.maxTokens());
        }
        if (options.temperature() != null) {
            request.put("temperature", options.temperature());
        }
    }

    private static String textOnly(List<ContentBlock> blocks, String role) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextContent value) {
                text.append(value.text());
            } else {
                throw unsupported("Mate Chat supports text-only " + role + " content");
            }
        }
        return text.toString();
    }

    private static String textOnlyAssistant(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextContent value) {
                text.append(value.text());
            } else if (block instanceof ImageContent) {
                throw unsupported("Mate Chat does not support assistant images");
            } else if (!(block instanceof ThinkingContent) && !(block instanceof ToolCall)) {
                throw unsupported("Mate Chat assistant content is unsupported");
            }
        }
        return text.toString();
    }

    private String serializeArguments(ToolCall call) {
        try {
            return mapper.writeValueAsString(call.arguments());
        } catch (JsonProcessingException error) {
            throw MateInvocationFailures.raise(
                    "mate.request.toolArguments.serialize",
                    MateInvocationErrorCode.INVALID_TOOL_ARGUMENTS,
                    error,
                    "toolName",
                    call.name());
        }
    }

    private static MateModelInvocationException unsupported(String message) {
        return MateInvocationFailures.raise(
                "mate.request.map", MateInvocationErrorCode.UNSUPPORTED_MATE_CHAT_CONTENT, "reason", message);
    }
}
