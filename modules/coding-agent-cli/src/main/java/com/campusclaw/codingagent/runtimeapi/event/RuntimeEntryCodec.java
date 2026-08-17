/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.ImageContent;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * 在 pi Message、数据库 Entry 和公共事件 JSON 之间执行受控投影。
 *
 * <p>原始 ThinkingContent 不进入公共事件，避免把模型内部思考过程作为接口契约披露。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeEntryCodec {
    private final ObjectMapper objectMapper;

    public RuntimeEntryCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RuntimeEntryDTO userEntry(
            String sessionId, String entryId, String message, List<String> fileIds, OffsetDateTime createdAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (message != null) {
            payload.put("message", message);
        }
        ArrayNode files = payload.putArray("file_ids");
        fileIds.forEach(files::add);
        return entry(sessionId, entryId, "user.message", createdAt, payload);
    }

    public RuntimeEntryDTO assistantEntry(
            String sessionId, String entryId, AssistantMessage message, OffsetDateTime fallbackTime) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode publicMessage = payload.putObject("message");
        publicMessage.put("role", "assistant");
        ArrayNode content = publicMessage.putArray("content");
        message.content().forEach(block -> appendPublicContent(content, block));
        payload.put("finish_reason", finishReason(message.stopReason()));
        return entry(
                sessionId,
                entryId,
                "assistant.message.completed",
                eventTime(message.timestamp(), fallbackTime),
                payload);
    }

    public RuntimeEntryDTO toolResultEntry(
            String sessionId, String entryId, ToolResultMessage message, OffsetDateTime fallbackTime) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tool_call_id", message.toolCallId());
        payload.put("tool_name", message.toolName());
        ArrayNode content = payload.putArray("content");
        message.content().forEach(block -> appendPublicContent(content, block));
        payload.put("is_error", message.isError());
        return entry(sessionId, entryId, "tool.result", eventTime(message.timestamp(), fallbackTime), payload);
    }

    public Map<String, Object> toSseData(RuntimeEntryDTO entry) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("entry_id", entry.getId());
        result.put("entry_seq", entry.getEntrySeq());
        appendPayload(result, entry.getPayload());
        result.put("created_at", entry.getTimestamp().toString());
        return result;
    }

    public Map<String, Object> toHistoryEvent(RuntimeEntryDTO entry) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("type", entry.getType());
        result.putAll(toSseData(entry));
        return result;
    }

    public List<Message> toAgentMessages(
            String sessionId, List<RuntimeEntryDTO> entries, Model model, RuntimeFileResolver fileResolver) {
        List<Message> messages = new ArrayList<>();
        for (RuntimeEntryDTO entry : entries) {
            messages.add(toAgentMessage(sessionId, entry, model, fileResolver));
        }
        return List.copyOf(messages);
    }

    private Message toAgentMessage(
            String sessionId, RuntimeEntryDTO entry, Model model, RuntimeFileResolver fileResolver) {
        JsonNode payload = readPayload(entry.getPayload());
        return switch (entry.getType()) {
            case "user.message" -> userMessage(sessionId, entry, payload, fileResolver);
            case "assistant.message.completed" -> assistantMessage(entry, payload, model);
            case "tool.result" -> toolResultMessage(entry, payload);
            default -> throw new IllegalArgumentException("unsupported runtime entry type: " + entry.getType());
        };
    }

    private UserMessage userMessage(
            String sessionId, RuntimeEntryDTO entry, JsonNode payload, RuntimeFileResolver fileResolver) {
        List<ContentBlock> content = new ArrayList<>();
        JsonNode message = payload.get("message");
        if (message != null && message.isTextual()) {
            content.add(new TextContent(message.asText()));
        }
        List<String> fileIds = new ArrayList<>();
        payload.path("file_ids").forEach(file -> fileIds.add(file.asText()));
        content.addAll(fileResolver.resolve(sessionId, fileIds));
        return new UserMessage(
                List.copyOf(content), entry.getTimestamp().toInstant().toEpochMilli());
    }

    private AssistantMessage assistantMessage(RuntimeEntryDTO entry, JsonNode payload, Model model) {
        List<ContentBlock> content = readPublicContent(payload.path("message").path("content"));
        StopReason reason = parseFinishReason(payload.path("finish_reason").asText());
        return new AssistantMessage(
                content,
                model.api().value(),
                model.provider().value(),
                model.id(),
                null,
                Usage.empty(),
                reason,
                null,
                entry.getTimestamp().toInstant().toEpochMilli());
    }

    private ToolResultMessage toolResultMessage(RuntimeEntryDTO entry, JsonNode payload) {
        return new ToolResultMessage(
                payload.path("tool_call_id").asText(),
                payload.path("tool_name").asText(),
                readPublicContent(payload.path("content")),
                null,
                payload.path("is_error").asBoolean(),
                entry.getTimestamp().toInstant().toEpochMilli());
    }

    private List<ContentBlock> readPublicContent(JsonNode content) {
        List<ContentBlock> result = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                result.add(new TextContent(block.path("text").asText()));
            } else if ("tool_call".equals(block.path("type").asText())) {
                result.add(readToolCall(block));
            } else if ("image".equals(block.path("type").asText())) {
                result.add(new ImageContent(
                        block.path("data").asText(), block.path("mime_type").asText()));
            }
        }
        return List.copyOf(result);
    }

    private ToolCall readToolCall(JsonNode block) {
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = objectMapper.convertValue(block.path("arguments"), Map.class);
        return new ToolCall(
                block.path("tool_call_id").asText(), block.path("name").asText(), arguments);
    }

    private void appendPublicContent(ArrayNode target, ContentBlock block) {
        if (block instanceof TextContent text) {
            target.addObject().put("type", "text").put("text", text.text());
        } else if (block instanceof ToolCall call) {
            ObjectNode output = target.addObject();
            output.put("type", "tool_call");
            output.put("tool_call_id", call.id());
            output.put("name", call.name());
            output.set("arguments", objectMapper.valueToTree(call.arguments()));
        } else if (block instanceof ImageContent image) {
            target.addObject().put("type", "image").put("data", image.data()).put("mime_type", image.mimeType());
        }
    }

    private RuntimeEntryDTO entry(
            String sessionId, String entryId, String type, OffsetDateTime timestamp, JsonNode payload) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId(sessionId);
        entry.setId(entryId);
        entry.setType(type);
        entry.setTimestamp(timestamp);
        try {
            entry.setPayload(objectMapper.writeValueAsString(payload));
            return entry;
        } catch (Exception error) {
            throw new IllegalStateException("failed to encode runtime entry", error);
        }
    }

    private void appendPayload(LinkedHashMap<String, Object> target, String payload) {
        JsonNode node = readPayload(payload);
        node.fields()
                .forEachRemaining(
                        field -> target.put(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class)));
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception error) {
            throw new IllegalStateException("failed to decode runtime entry", error);
        }
    }

    private static OffsetDateTime eventTime(long epochMillis, OffsetDateTime fallback) {
        if (epochMillis <= 0) {
            return fallback;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private static String finishReason(StopReason reason) {
        return switch (reason) {
            case TOOL_USE -> "tool_call";
            case STOP -> "stop";
            case LENGTH -> "length";
            case ERROR -> "error";
            case ABORTED -> "aborted";
        };
    }

    private static StopReason parseFinishReason(String reason) {
        return switch (reason) {
            case "tool_call" -> StopReason.TOOL_USE;
            case "length" -> StopReason.LENGTH;
            case "error" -> StopReason.ERROR;
            case "aborted" -> StopReason.ABORTED;
            default -> StopReason.STOP;
        };
    }
}
