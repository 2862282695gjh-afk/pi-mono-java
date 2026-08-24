/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.ai.utils.ContextOverflowDetector;

import org.springframework.stereotype.Component;

/**
 * 为公共 Agent Session 生成压缩摘要并计算保留窗口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class SessionCompactor {
    private static final String SUMMARIZATION_PROMPT =
            """
            Summarize the conversation for an agent that must continue the work.
            Preserve decisions and reasons, current state, files read, errors, and remaining work.
            Do not invent facts. Keep the summary concise but complete.
            """;

    private final CampusClawAiService aiService;

    private final CompactionProperties properties;

    public SessionCompactor(CampusClawAiService aiService, CompactionProperties properties) {
        this.aiService = aiService;
        this.properties = properties;
    }

    public boolean isOverflow(List<Message> messages, Model model) {
        AssistantMessage last = lastAssistant(messages);
        return last != null && ContextOverflowDetector.isContextOverflow(last, model.contextWindow());
    }

    public boolean exceedsThreshold(List<Message> messages, Model model) {
        if (!properties.isEnabled()) {
            return false;
        }
        int threshold = Math.max(1, model.contextWindow() - properties.getReserveTokens());
        return estimateTokens(messages) > threshold;
    }

    public CompletableFuture<SessionCompactionResult> compact(
            List<Message> messages, Model model, ThinkingLevel thinking, String customInstructions) {
        PreparedCompaction prepared = prepare(messages);
        String prompt = buildPrompt(prepared.oldMessages(), customInstructions);
        Context context =
                new Context(SUMMARIZATION_PROMPT, List.of(new UserMessage(prompt, System.currentTimeMillis())), null);
        SimpleStreamOptions options =
                SimpleStreamOptions.builder().reasoning(thinking).build();
        return aiService
                .completeSimple(model, context, options)
                .map(message -> result(prepared, requireSummary(message), message))
                .toFuture();
    }

    public int estimateTokens(List<Message> messages) {
        return messages.stream()
                .mapToInt(SessionCompactor::estimateMessageTokens)
                .sum();
    }

    private PreparedCompaction prepare(List<Message> messages) {
        if (messages.size() < 2) {
            throw new IllegalStateException("Session has no compactable history");
        }
        int tokensBefore = estimateTokens(messages);
        int splitIndex = findSplitIndex(messages);
        if (splitIndex <= 0) {
            throw new IllegalStateException("Session has no compactable history");
        }
        List<Message> oldMessages = List.copyOf(messages.subList(0, splitIndex));
        List<Message> retained = List.copyOf(messages.subList(splitIndex, messages.size()));
        return new PreparedCompaction(oldMessages, retained, tokensBefore);
    }

    private int findSplitIndex(List<Message> messages) {
        int recentTokens = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            int tokens = estimateMessageTokens(messages.get(index));
            if (recentTokens + tokens > properties.getKeepRecentTokens()) {
                return index + 1;
            }
            recentTokens += tokens;
        }
        return Math.max(1, messages.size() - 1);
    }

    private SessionCompactionResult result(PreparedCompaction prepared, String summary, AssistantMessage response) {
        List<Message> compacted = new ArrayList<>();
        compacted.add(summaryMessage(summary));
        compacted.addAll(prepared.retainedMessages());
        int tokensAfter = estimateTokens(compacted);
        return new SessionCompactionResult(
                summary,
                prepared.retainedMessages(),
                prepared.oldMessages().size(),
                prepared.tokensBefore(),
                tokensAfter,
                response.usage());
    }

    private static UserMessage summaryMessage(String summary) {
        return new UserMessage("[Context compaction summary]\n" + summary, System.currentTimeMillis());
    }

    private static String requireSummary(AssistantMessage response) {
        if (response.stopReason() == StopReason.ERROR || response.stopReason() == StopReason.ABORTED) {
            throw new IllegalStateException("Compaction model call did not complete");
        }
        return response.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Compaction model returned no summary"));
    }

    private static String buildPrompt(List<Message> messages, String customInstructions) {
        StringBuilder prompt = new StringBuilder();
        Set<String> filesRead = FileOperationTracker.filesRead(messages);
        if (!filesRead.isEmpty()) {
            prompt.append("Files read: ").append(String.join(", ", filesRead)).append("\n\n");
        }
        if (customInstructions != null && !customInstructions.isBlank()) {
            prompt.append("Additional instructions: ")
                    .append(customInstructions.trim())
                    .append("\n\n");
        }
        prompt.append("Conversation to summarize:\n\n");
        messages.forEach(message -> prompt.append(serialize(message)).append('\n'));
        return prompt.toString();
    }

    private static String serialize(Message message) {
        if (message instanceof UserMessage user) {
            return "User: " + text(user.content());
        }
        if (message instanceof AssistantMessage assistant) {
            return "Assistant: " + assistantText(assistant.content());
        }
        if (message instanceof ToolResultMessage result) {
            return "ToolResult(" + result.toolCallId() + "): " + text(result.content());
        }
        return "";
    }

    private static String assistantText(List<ContentBlock> content) {
        StringBuilder result = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextContent text) {
                result.append(text.text());
            } else if (block instanceof ToolCall call) {
                result.append("[Tool: ").append(call.name()).append(']');
            }
        }
        return result.toString();
    }

    private static String text(List<ContentBlock> content) {
        return content.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
    }

    private static int estimateMessageTokens(Message message) {
        List<ContentBlock> content = content(message);
        int characters = 0;
        for (ContentBlock block : content) {
            characters += contentCharacters(block);
        }
        return Math.max(1, characters / 4);
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
        if (block instanceof ThinkingContent thinking) {
            return thinking.thinking().length();
        }
        if (block instanceof ToolCall call) {
            int arguments =
                    call.arguments() == null ? 0 : call.arguments().toString().length();
            return call.name().length() + arguments;
        }
        return 0;
    }

    private static AssistantMessage lastAssistant(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistant) {
                return assistant;
            }
        }
        return null;
    }

    private record PreparedCompaction(List<Message> oldMessages, List<Message> retainedMessages, int tokensBefore) {}
}
