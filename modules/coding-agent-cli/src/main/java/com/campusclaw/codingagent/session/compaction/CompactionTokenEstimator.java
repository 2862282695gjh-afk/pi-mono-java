/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import java.util.List;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.ImageContent;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结合最近一次有效模型用量和后续消息内容估算上下文压缩所需的 Token 数量。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
final class CompactionTokenEstimator {
    private static final int ESTIMATED_IMAGE_CHARS = 4_800;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompactionTokenEstimator() {}

    static int calculateContextTokens(Usage usage) {
        if (usage == null) {
            return 0;
        }
        if (usage.totalTokens() > 0) {
            return usage.totalTokens();
        }
        return usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
    }

    static ContextUsageEstimate estimateContextTokens(List<Message> messages) {
        UsageSource source = lastValidUsage(messages);
        if (source == null) {
            int estimated = estimateMessages(messages);
            return new ContextUsageEstimate(estimated, 0, estimated, -1);
        }
        int trailing = estimateMessages(messages.subList(source.index() + 1, messages.size()));
        int usageTokens = calculateContextTokens(source.message().usage());
        return new ContextUsageEstimate(usageTokens + trailing, usageTokens, trailing, source.index());
    }

    static int estimateMessages(List<Message> messages) {
        return messages.stream()
                .mapToInt(CompactionTokenEstimator::estimateMessage)
                .sum();
    }

    static int estimateMessage(Message message) {
        int characters = content(message).stream()
                .mapToInt(CompactionTokenEstimator::contentCharacters)
                .sum();
        return (characters + 3) / 4;
    }

    private static UsageSource lastValidUsage(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof AssistantMessage assistant && hasValidUsage(assistant)) {
                return new UsageSource(index, assistant);
            }
        }
        return null;
    }

    private static boolean hasValidUsage(AssistantMessage message) {
        return message.stopReason() != StopReason.ABORTED
                && message.stopReason() != StopReason.ERROR
                && calculateContextTokens(message.usage()) > 0;
    }

    private static List<ContentBlock> content(Message message) {
        if (message instanceof UserMessage user) {
            return user.content();
        }
        if (message instanceof AssistantMessage assistant) {
            return assistant.content();
        }
        if (message instanceof ToolResultMessage result) {
            return result.content();
        }
        return List.of();
    }

    private static int contentCharacters(ContentBlock block) {
        if (block instanceof TextContent text) {
            return text.text().length();
        }
        if (block instanceof ImageContent) {
            return ESTIMATED_IMAGE_CHARS;
        }
        if (block instanceof ThinkingContent thinking) {
            return thinking.thinking().length();
        }
        if (block instanceof ToolCall call) {
            return call.name().length() + jsonLength(call.arguments());
        }
        return 0;
    }

    private static int jsonLength(Object value) {
        try {
            return MAPPER.writeValueAsString(value).length();
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Tool arguments cannot be serialized", error);
        }
    }

    record ContextUsageEstimate(int tokens, int usageTokens, int trailingTokens, int lastUsageIndex) {}

    private record UsageSource(int index, AssistantMessage message) {}
}
