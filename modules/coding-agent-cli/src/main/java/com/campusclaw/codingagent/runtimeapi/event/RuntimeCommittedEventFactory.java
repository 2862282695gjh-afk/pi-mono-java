/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 从已安全定稿的运行时对象生成 v2 权威公共事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeCommittedEventFactory {
    private static final String GENERIC_TOOL_ERROR = "TOOL_EXECUTION_FAILED";

    private final ObjectMapper objectMapper;

    private final MessageSource messageSource;

    public RuntimeCommittedEventFactory(ObjectMapper objectMapper, MessageSource messageSource) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    public CommittedEventDTO userMessage(RuntimeEntryDTO entry, String publicText, List<String> fileIds) {
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode content = payload.putArray("content");
        if (publicText != null) {
            content.addObject().put("type", "text").put("text", publicText);
        }
        fileIds.forEach(fileId -> content.addObject().put("type", "file").put("fileId", fileId));
        return create(entry, entry.getId(), CommittedEventType.USER_MESSAGE, payload);
    }

    public CommittedEventDTO agentMessage(
            RuntimeEntryDTO entry, String eventId, AssistantMessage message, String sourceEventId) {
        ObjectNode payload = sourcePayload(sourceEventId);
        payload.put("phase", "completed");
        payload.put("content", textContent(message.content()));
        appendUsage(payload, message.usage());
        return create(entry, eventId, CommittedEventType.AGENT_MESSAGE, payload);
    }

    public CommittedEventDTO agentThinking(
            RuntimeEntryDTO entry, String eventId, String content, String sourceEventId) {
        ObjectNode payload = sourcePayload(sourceEventId);
        payload.put("phase", "completed");
        payload.put("content", content);
        return create(entry, eventId, CommittedEventType.AGENT_THINKING, payload);
    }

    public CommittedEventDTO agentToolCall(
            RuntimeEntryDTO entry, String eventId, ToolCall call, boolean requiresConfirmation, String sourceEventId) {
        ObjectNode payload = sourcePayload(sourceEventId);
        payload.put("toolCallId", call.id());
        payload.put("toolName", call.name());
        payload.set("arguments", objectMapper.valueToTree(call.arguments() == null ? Map.of() : call.arguments()));
        payload.put("requiresConfirmation", requiresConfirmation);
        return create(entry, eventId, CommittedEventType.AGENT_TOOL_CALL, payload);
    }

    public CommittedEventDTO agentToolResult(
            RuntimeEntryDTO entry, String eventId, ToolResultMessage result, String sourceEventId, Locale locale) {
        ObjectNode payload = sourcePayload(sourceEventId);
        payload.put("toolCallId", result.toolCallId());
        appendToolContent(payload.putArray("content"), result, locale);
        payload.put("isError", result.isError());
        if (result.isError()) {
            payload.put("errorCode", toolErrorCode(result));
        }
        return create(entry, eventId, CommittedEventType.AGENT_TOOL_RESULT, payload);
    }

    public CommittedEventDTO sessionIdle(
            RuntimeEntryDTO entry,
            String eventId,
            String reason,
            String sourceEventId,
            String errorCode,
            Locale locale) {
        ObjectNode payload = sourcePayload(sourceEventId);
        payload.put("reason", reason);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
            payload.put("message", messageSource.getMessage(errorCode, null, locale));
        }
        return create(entry, eventId, CommittedEventType.SESSION_STATUS_IDLE, payload);
    }

    public CommittedEventDTO sessionCompacted(
            RuntimeEntryDTO entry,
            String eventId,
            String reason,
            long tokensBefore,
            long estimatedTokensAfter,
            String sourceEventId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reason", reason);
        payload.put("tokensBefore", tokensBefore);
        payload.put("estimatedTokensAfter", estimatedTokensAfter);
        if (sourceEventId != null) {
            payload.put("sourceEventId", sourceEventId);
        }
        return create(entry, eventId, CommittedEventType.SESSION_COMPACTED, payload);
    }

    private ObjectNode sourcePayload(String sourceEventId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceEventId", sourceEventId);
        return payload;
    }

    private String textContent(List<ContentBlock> content) {
        return content.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
    }

    private void appendUsage(ObjectNode payload, Usage usage) {
        if (usage == null) {
            return;
        }
        ObjectNode value = payload.putObject("usage");
        value.put("input", usage.input());
        value.put("output", usage.output());
        value.put("cacheRead", usage.cacheRead());
        value.put("cacheWrite", usage.cacheWrite());
        value.put("totalTokens", usage.totalTokens());
        appendCost(value, usage.cost());
    }

    private void appendCost(ObjectNode usage, Cost cost) {
        if (cost == null) {
            return;
        }
        ObjectNode value = usage.putObject("cost");
        value.put("input", cost.input());
        value.put("output", cost.output());
        value.put("cacheRead", cost.cacheRead());
        value.put("cacheWrite", cost.cacheWrite());
        value.put("total", cost.total());
    }

    private void appendToolContent(ArrayNode content, ToolResultMessage result, Locale locale) {
        result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .forEach(text -> content.addObject().put("type", "text").put("text", text.text()));
        if (content.isEmpty() && result.isError()) {
            content.addObject()
                    .put("type", "text")
                    .put("text", messageSource.getMessage(toolErrorCode(result), null, locale));
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("tool result must contain text content");
        }
    }

    private static String toolErrorCode(ToolResultMessage result) {
        if (result.details() instanceof Map<?, ?> details) {
            Object value = details.get("errorCode");
            if (value != null && ClawConstants.RuntimeApi.TOOL_ERROR_CODES.contains(value.toString())) {
                return value.toString();
            }
        }
        return GENERIC_TOOL_ERROR;
    }

    private CommittedEventDTO create(
            RuntimeEntryDTO entry, String eventId, CommittedEventType type, ObjectNode payload) {
        return create(entry.getSessionId(), eventId, entry.getId(), type, entry.getTimestamp(), writePayload(payload));
    }

    private CommittedEventDTO create(
            String sessionId,
            String eventId,
            String anchorEntryId,
            CommittedEventType type,
            OffsetDateTime createdAt,
            String payload) {
        CommittedEventDTO event = new CommittedEventDTO();
        event.setSessionId(sessionId);
        event.setEventId(eventId);
        event.setAnchorEntryId(anchorEntryId);
        event.setType(type.value());
        event.setCreatedAt(createdAt);
        event.setPayload(payload);
        return event;
    }

    private String writePayload(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("failed to encode committed event", error);
        }
    }
}
