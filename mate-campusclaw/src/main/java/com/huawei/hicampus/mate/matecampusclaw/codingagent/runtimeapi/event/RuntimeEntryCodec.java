/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ImageContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.CompactionMessageSupport;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.CompactionReason;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * 在 pi Message、数据库 Entry 和公共事件 JSON 之间执行受控投影。
 *
 * <p>Assistant MessageEntry 过滤 ThinkingContent；公开 thinking completed 使用独立持久化 Entry。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
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
        return entry(sessionId, entryId, RuntimeEventType.USER_MESSAGE.value(), createdAt, payload);
    }

    public RuntimeEntryDTO assistantEntry(
            String sessionId, String entryId, AssistantMessage message, OffsetDateTime fallbackTime) {
        Usage usage = message.usage() == null ? Usage.empty() : message.usage();
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode publicMessage = payload.putObject("message");
        publicMessage.put("role", "assistant");
        ArrayNode content = publicMessage.putArray("content");
        message.content().forEach(block -> appendPublicContent(content, block));
        payload.put("finish_reason", finishReason(message.stopReason()));
        payload.put("_api", message.api());
        payload.put("_provider", message.provider());
        payload.put("_model", message.model());
        payload.set("usage", objectMapper.valueToTree(usage));
        RuntimeEntryDTO entry = entry(
                sessionId,
                entryId,
                RuntimeEventType.ASSISTANT_MESSAGE_COMPLETED.value(),
                eventTime(message.timestamp(), fallbackTime),
                payload);
        entry.setUsage(usage);
        return entry;
    }

    public RuntimeEntryDTO thinkingEntry(
            String sessionId,
            String entryId,
            String assistantEntryId,
            int contentIndex,
            String content,
            OffsetDateTime createdAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("assistant_entry_id", assistantEntryId);
        payload.put("content_index", contentIndex);
        payload.putObject("content").put("type", "thinking").put("text", content);
        return entry(sessionId, entryId, RuntimeEventType.ASSISTANT_THINKING_COMPLETED.value(), createdAt, payload);
    }

    public RuntimeEntryDTO toolResultEntry(
            String sessionId, String entryId, ToolResultMessage message, OffsetDateTime fallbackTime) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tool_call_id", message.toolCallId());
        payload.put("tool_name", message.toolName());
        ArrayNode content = payload.putArray("content");
        message.content().forEach(block -> appendPublicContent(content, block));
        payload.put("is_error", message.isError());
        return entry(
                sessionId,
                entryId,
                RuntimeEventType.TOOL_RESULT.value(),
                eventTime(message.timestamp(), fallbackTime),
                payload);
    }

    public RuntimeEntryDTO modelChangedEntry(
            String sessionId,
            String entryId,
            String previousModelId,
            String modelId,
            String reason,
            OffsetDateTime createdAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("previousModelId", previousModelId);
        payload.put("modelId", modelId);
        payload.put("reason", reason);
        return entry(sessionId, entryId, RuntimeEventType.SESSION_MODEL_CHANGED.value(), createdAt, payload);
    }

    public RuntimeEntryDTO thinkingChangedEntry(
            String sessionId,
            String entryId,
            boolean previousThinking,
            boolean thinking,
            String reason,
            OffsetDateTime createdAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("previousThinking", previousThinking);
        payload.put("thinking", thinking);
        payload.put("reason", reason);
        return entry(sessionId, entryId, RuntimeEventType.SESSION_THINKING_CHANGED.value(), createdAt, payload);
    }

    public RuntimeEntryDTO compactionEntry(
            String sessionId,
            String entryId,
            CompactionReason reason,
            String firstKeptEntryId,
            SessionCompactionResult result,
            boolean willRetry,
            OffsetDateTime createdAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reason", reason.value());
        payload.put("summary", result.summary());
        payload.put("firstKeptEntryId", firstKeptEntryId);
        payload.put("tokensBefore", result.tokensBefore());
        payload.put("estimatedTokensAfter", result.estimatedTokensAfter());
        payload.put("willRetry", willRetry);
        payload.set("usage", objectMapper.valueToTree(result.usage()));
        RuntimeEntryDTO entry =
                entry(sessionId, entryId, RuntimeEventType.SESSION_COMPACTION_COMPLETED.value(), createdAt, payload);
        entry.setUsage(result.usage());
        return entry;
    }

    public Map<String, Object> toSseData(RuntimeEntryDTO entry) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("entryId", entry.getId());
        result.put("entrySeq", entry.getEntrySeq());
        appendPublicPayload(result, entry);
        result.put("createdAt", entry.getTimestamp().toString());
        return result;
    }

    public Map<String, Object> toHistoryEvent(RuntimeEntryDTO entry) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("type", entry.getType());
        result.putAll(toSseData(entry));
        return result;
    }

    public long encodedSseBytes(RuntimeSseEventVO event) {
        try {
            return objectMapper.writeValueAsBytes(event).length;
        } catch (Exception error) {
            throw new IllegalStateException("failed to size runtime SSE event", error);
        }
    }

    public UserMessage toUserMessage(String message, List<String> fileIds, long timestamp) {
        return new UserMessage(List.of(new TextContent(toAgentPrompt(message, fileIds))), timestamp);
    }

    public List<Message> toAgentMessages(List<RuntimeEntryDTO> entries, Model model) {
        List<Message> messages = new ArrayList<>();
        for (RuntimeEntryDTO entry : effectiveContextEntries(entries)) {
            Message message = toAgentMessage(entry, model);
            if (message != null) {
                messages.add(message);
            }
        }
        return List.copyOf(messages);
    }

    public List<String> toAgentContextEntryIds(List<RuntimeEntryDTO> entries) {
        List<String> ids = new ArrayList<>();
        for (RuntimeEntryDTO entry : effectiveContextEntries(entries)) {
            if (isAgentContextEntry(entry)) {
                ids.add(entry.getId());
            }
        }
        return List.copyOf(ids);
    }

    private Message toAgentMessage(RuntimeEntryDTO entry, Model model) {
        JsonNode payload = readPayload(entry.getPayload());
        return switch (RuntimeEventType.fromValue(entry.getType())) {
            case USER_MESSAGE -> userMessage(entry, payload);
            case ASSISTANT_MESSAGE_COMPLETED -> assistantMessage(entry, payload, model);
            case TOOL_RESULT -> toolResultMessage(entry, payload);
            case SESSION_COMPACTION_COMPLETED -> compactionSummaryMessage(entry, payload);
            default -> null;
        };
    }

    private UserMessage userMessage(RuntimeEntryDTO entry, JsonNode payload) {
        JsonNode message = payload.get("message");
        String text = message != null && message.isTextual() ? message.asText() : null;
        List<String> fileIds = new ArrayList<>();
        payload.path("file_ids").forEach(file -> fileIds.add(file.asText()));
        return toUserMessage(text, fileIds, entry.getTimestamp().toInstant().toEpochMilli());
    }

    private static String toAgentPrompt(String message, List<String> fileIds) {
        StringBuilder prompt = new StringBuilder();
        if (message != null) {
            prompt.append(message);
        }
        if (!fileIds.isEmpty()) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append("[File IDs]");
            fileIds.forEach(fileId -> prompt.append("\n- file_id: ").append(fileId));
        }
        return prompt.toString();
    }

    private AssistantMessage assistantMessage(RuntimeEntryDTO entry, JsonNode payload, Model model) {
        List<ContentBlock> content = readPublicContent(payload.path("message").path("content"));
        StopReason reason = parseFinishReason(payload.path("finish_reason").asText());
        return new AssistantMessage(
                content,
                payload.path("_api").asText(model.api().value()),
                payload.path("_provider").asText(model.provider().value()),
                payload.path("_model").asText(model.id()),
                null,
                readUsage(payload.path("usage")),
                reason,
                null,
                entry.getTimestamp().toInstant().toEpochMilli());
    }

    private UserMessage compactionSummaryMessage(RuntimeEntryDTO entry, JsonNode payload) {
        String summary = payload.path("summary").asText();
        return CompactionMessageSupport.summaryMessage(
                summary, entry.getTimestamp().toInstant().toEpochMilli());
    }

    private List<RuntimeEntryDTO> effectiveContextEntries(List<RuntimeEntryDTO> entries) {
        int compactionIndex = latestCompactionIndex(entries);
        if (compactionIndex < 0) {
            return recoverableContextEntries(entries, 0, entries.size());
        }
        RuntimeEntryDTO compaction = entries.get(compactionIndex);
        String firstKeptEntryId =
                readPayload(compaction.getPayload()).path("firstKeptEntryId").asText();
        int firstKeptIndex = findEntryIndex(entries, firstKeptEntryId);
        List<RuntimeEntryDTO> result = new ArrayList<>();
        result.add(compaction);
        if (firstKeptIndex >= 0) {
            appendContextEntries(entries, firstKeptIndex, compactionIndex, result);
        }
        appendContextEntries(entries, compactionIndex + 1, entries.size(), result);
        return List.copyOf(result);
    }

    private void appendContextEntries(List<RuntimeEntryDTO> entries, int start, int end, List<RuntimeEntryDTO> target) {
        for (int index = start; index < end; index++) {
            RuntimeEntryDTO entry = entries.get(index);
            if (isRecoverableMessageEntry(entry)) {
                target.add(entry);
            }
        }
    }

    private List<RuntimeEntryDTO> recoverableContextEntries(List<RuntimeEntryDTO> entries, int start, int end) {
        List<RuntimeEntryDTO> result = new ArrayList<>();
        appendContextEntries(entries, start, end, result);
        return List.copyOf(result);
    }

    private static int latestCompactionIndex(List<RuntimeEntryDTO> entries) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (RuntimeEventType.SESSION_COMPACTION_COMPLETED
                    .value()
                    .equals(entries.get(index).getType())) {
                return index;
            }
        }
        return -1;
    }

    private static int findEntryIndex(List<RuntimeEntryDTO> entries, String entryId) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).getId().equals(entryId)) {
                return index;
            }
        }
        return -1;
    }

    private boolean isAgentContextEntry(RuntimeEntryDTO entry) {
        return isRecoverableMessageEntry(entry)
                || RuntimeEventType.SESSION_COMPACTION_COMPLETED.value().equals(entry.getType());
    }

    private boolean isRecoverableMessageEntry(RuntimeEntryDTO entry) {
        String type = entry.getType();
        if (RuntimeEventType.ASSISTANT_MESSAGE_COMPLETED.value().equals(type)) {
            String reason = field(readPayload(entry.getPayload()), "finishReason", "finish_reason")
                    .asText();
            return !"error".equals(reason) && !"aborted".equals(reason);
        }
        return RuntimeEventType.USER_MESSAGE.value().equals(type)
                || RuntimeEventType.TOOL_RESULT.value().equals(type);
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

    private void appendPublicPayload(LinkedHashMap<String, Object> target, RuntimeEntryDTO entry) {
        JsonNode payload = readPayload(entry.getPayload());
        switch (RuntimeEventType.fromValue(entry.getType())) {
            case USER_MESSAGE -> appendUserPayload(target, payload);
            case ASSISTANT_MESSAGE_COMPLETED -> appendAssistantPayload(target, payload);
            case ASSISTANT_THINKING_COMPLETED -> appendThinkingPayload(target, payload);
            case TOOL_RESULT -> appendToolResultPayload(target, payload);
            case SESSION_MODEL_CHANGED -> appendModelChangedPayload(target, payload);
            case SESSION_THINKING_CHANGED -> appendThinkingChangedPayload(target, payload);
            case SESSION_COMPACTION_COMPLETED -> appendCompactionPayload(target, payload);
            default -> throw new IllegalArgumentException("unsupported public runtime entry type: " + entry.getType());
        }
    }

    private void appendUserPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        JsonNode message = payload.get("message");
        if (message != null) {
            target.put("message", objectMapper.convertValue(message, Object.class));
        }
        target.put("fileIds", objectMapper.convertValue(field(payload, "fileIds", "file_ids"), Object.class));
    }

    private void appendAssistantPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        target.put("message", publicMessage(payload.path("message")));
        target.put(
                "finishReason",
                objectMapper.convertValue(field(payload, "finishReason", "finish_reason"), Object.class));
        target.put("usage", objectMapper.convertValue(payload.path("usage"), Object.class));
    }

    private void appendThinkingPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        target.put(
                "assistantEntryId",
                objectMapper.convertValue(field(payload, "assistantEntryId", "assistant_entry_id"), Object.class));
        target.put(
                "contentIndex",
                objectMapper.convertValue(field(payload, "contentIndex", "content_index"), Object.class));
        target.put("content", objectMapper.convertValue(payload.path("content"), Object.class));
    }

    private void appendToolResultPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        target.put("toolCallId", objectMapper.convertValue(field(payload, "toolCallId", "tool_call_id"), Object.class));
        target.put("toolName", objectMapper.convertValue(field(payload, "toolName", "tool_name"), Object.class));
        target.put("content", publicContent(payload.path("content")));
        target.put("isError", objectMapper.convertValue(field(payload, "isError", "is_error"), Object.class));
    }

    private void appendModelChangedPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        target.put("previousModelId", payload.path("previousModelId").asText());
        target.put("modelId", payload.path("modelId").asText());
        target.put("reason", payload.path("reason").asText());
    }

    private void appendThinkingChangedPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        target.put("previousThinking", payload.path("previousThinking").asBoolean());
        target.put("thinking", payload.path("thinking").asBoolean());
        target.put("reason", payload.path("reason").asText());
    }

    private void appendCompactionPayload(LinkedHashMap<String, Object> target, JsonNode payload) {
        target.put("reason", payload.path("reason").asText());
        target.put("summary", payload.path("summary").asText());
        target.put("firstKeptEntryId", payload.path("firstKeptEntryId").asText());
        target.put("tokensBefore", payload.path("tokensBefore").asInt());
        target.put("estimatedTokensAfter", payload.path("estimatedTokensAfter").asInt());
        target.put("willRetry", payload.path("willRetry").asBoolean());
        target.put("usage", objectMapper.convertValue(payload.path("usage"), Object.class));
    }

    private Map<String, Object> publicMessage(JsonNode message) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", message.path("role").asText());
        result.put("content", publicContent(message.path("content")));
        return result;
    }

    private List<Map<String, Object>> publicContent(JsonNode content) {
        List<Map<String, Object>> result = new ArrayList<>();
        content.forEach(block -> result.add(publicContentBlock(block)));
        return List.copyOf(result);
    }

    private Map<String, Object> publicContentBlock(JsonNode block) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        String type = block.path("type").asText();
        result.put("type", type);
        if ("text".equals(type)) {
            result.put("text", block.path("text").asText());
        } else if ("tool_call".equals(type)) {
            result.put("toolCallId", field(block, "toolCallId", "tool_call_id").asText());
            result.put("name", block.path("name").asText());
            result.put("arguments", objectMapper.convertValue(block.path("arguments"), Object.class));
        } else if ("image".equals(type)) {
            result.put("data", block.path("data").asText());
            result.put("mimeType", field(block, "mimeType", "mime_type").asText());
        }
        return result;
    }

    private static JsonNode field(JsonNode node, String camelCase, String snakeCase) {
        JsonNode value = node.get(camelCase);
        return value != null ? value : node.path(snakeCase);
    }

    private Usage readUsage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Usage.empty();
        }
        try {
            return objectMapper.treeToValue(node, Usage.class);
        } catch (Exception error) {
            throw new IllegalStateException("failed to decode runtime usage", error);
        }
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
