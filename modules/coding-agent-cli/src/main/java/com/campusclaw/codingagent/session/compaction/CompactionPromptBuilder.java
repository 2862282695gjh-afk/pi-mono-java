/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.UserMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 构建上下文压缩使用的系统提示词，并将会话消息序列化为历史摘要或回合前缀提示词。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
final class CompactionPromptBuilder {
    static final String SYSTEM_PROMPT =
            """
            You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

            Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.
            """;

    private static final int TOOL_RESULT_MAX_CHARS = 2_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUMMARY_PROMPT =
            """
            The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

            Use this EXACT format:

            ## Goal
            [What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

            ## Constraints & Preferences
            - [Any constraints, preferences, or requirements mentioned by user]
            - [Or "(none)" if none were mentioned]

            ## Progress
            ### Done
            - [x] [Completed tasks/changes]

            ### In Progress
            - [ ] [Current work]

            ### Blocked
            - [Issues preventing progress, if any]

            ## Key Decisions
            - **[Decision]**: [Brief rationale]

            ## Next Steps
            1. [Ordered list of what should happen next]

            ## Critical Context
            - [Any data, examples, or references needed to continue]
            - [Or "(none)" if not applicable]

            Keep each section concise. Preserve exact file paths, function names, and error messages.
            """;

    private static final String UPDATE_PROMPT =
            """
            The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

            Update the existing structured summary with new information. RULES:
            - PRESERVE all existing information from the previous summary
            - ADD new progress, decisions, and context from the new messages
            - UPDATE the Progress section: move items from "In Progress" to "Done" when completed
            - UPDATE "Next Steps" based on what was accomplished
            - PRESERVE exact file paths, function names, and error messages
            - If something is no longer relevant, you may remove it

            Use this EXACT format:

            ## Goal
            [Preserve existing goals, add new ones if the task expanded]

            ## Constraints & Preferences
            - [Preserve existing, add new ones discovered]

            ## Progress
            ### Done
            - [x] [Include previously done items AND newly completed items]

            ### In Progress
            - [ ] [Current work - update based on progress]

            ### Blocked
            - [Current blockers - remove if resolved]

            ## Key Decisions
            - **[Decision]**: [Brief rationale] (preserve all previous, add new)

            ## Next Steps
            1. [Update based on current state]

            ## Critical Context
            - [Preserve important context, add new if needed]

            Keep each section concise. Preserve exact file paths, function names, and error messages.
            """;

    private static final String TURN_PREFIX_PROMPT =
            """
            This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.

            Summarize the prefix to provide context for the retained suffix:

            ## Original Request
            [What did the user ask for in this turn?]

            ## Early Progress
            - [Key decisions and work done in the prefix]

            ## Context for Suffix
            - [Information needed to understand the retained recent work]

            Be concise. Focus on what's needed to understand the kept suffix.
            """;

    private CompactionPromptBuilder() {}

    static String historyPrompt(List<Message> messages, String previousSummary, String customInstructions) {
        StringBuilder prompt = conversation(messages);
        if (previousSummary != null) {
            prompt.append("\n\n<previous-summary>\n").append(previousSummary).append("\n</previous-summary>");
        }
        prompt.append("\n\n").append(previousSummary == null ? SUMMARY_PROMPT : UPDATE_PROMPT);
        if (customInstructions != null && !customInstructions.isBlank()) {
            prompt.append("\n\nAdditional focus: ").append(customInstructions.trim());
        }
        return prompt.toString();
    }

    static String turnPrefixPrompt(List<Message> messages) {
        return conversation(messages).append("\n\n").append(TURN_PREFIX_PROMPT).toString();
    }

    private static StringBuilder conversation(List<Message> messages) {
        return new StringBuilder("<conversation>\n")
                .append(serializeConversation(messages))
                .append("\n</conversation>");
    }

    private static String serializeConversation(List<Message> messages) {
        List<String> parts = new ArrayList<>();
        for (Message message : messages) {
            appendMessage(parts, message);
        }
        return String.join("\n\n", parts);
    }

    private static void appendMessage(List<String> parts, Message message) {
        if (message instanceof UserMessage user) {
            appendIfPresent(parts, "[User]: ", contentText(user.content()));
        } else if (message instanceof AssistantMessage assistant) {
            appendAssistant(parts, assistant);
        } else if (message instanceof ToolResultMessage result) {
            appendIfPresent(parts, "[Tool result]: ", truncate(contentText(result.content())));
        }
    }

    private static void appendAssistant(List<String> parts, AssistantMessage assistant) {
        List<String> thinking = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ThinkingContent value) {
                thinking.add(value.thinking());
            } else if (block instanceof ToolCall call) {
                calls.add(toolCall(call));
            }
        }
        appendIfPresent(parts, "[Assistant thinking]: ", String.join("\n", thinking));
        appendIfPresent(parts, "[Assistant]: ", contentText(assistant.content()));
        appendIfPresent(parts, "[Assistant tool calls]: ", String.join("; ", calls));
    }

    private static String toolCall(ToolCall call) {
        List<String> arguments = new ArrayList<>();
        if (call.arguments() != null) {
            for (Map.Entry<String, Object> entry : call.arguments().entrySet()) {
                arguments.add(entry.getKey() + "=" + json(entry.getValue()));
            }
        }
        return call.name() + "(" + String.join(", ", arguments) + ")";
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Tool argument cannot be serialized", error);
        }
    }

    private static String contentText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
    }

    private static String truncate(String text) {
        if (text.length() <= TOOL_RESULT_MAX_CHARS) {
            return text;
        }
        int removed = text.length() - TOOL_RESULT_MAX_CHARS;
        return text.substring(0, TOOL_RESULT_MAX_CHARS) + "\n\n[... " + removed + " more characters truncated]";
    }

    private static void appendIfPresent(List<String> parts, String prefix, String value) {
        if (value != null && !value.isEmpty()) {
            parts.add(prefix + value);
        }
    }
}
