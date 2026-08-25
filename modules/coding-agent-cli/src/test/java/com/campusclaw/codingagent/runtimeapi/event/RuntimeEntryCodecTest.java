/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 持久化 Entry 与 lowerCamelCase 公共事件之间的兼容投影测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/21]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEntryCodecTest {
    @Test
    void projectsStoredAssistantPayloadWithoutChangingToolArguments() {
        RuntimeEntryDTO entry = assistantEntry();

        Map<String, Object> event = new RuntimeEntryCodec(new ObjectMapper()).toHistoryEvent(entry);

        assertThat(event)
                .containsEntry("type", "assistant.message.completed")
                .containsEntry("entryId", "entry_101")
                .containsEntry("entrySeq", 19L)
                .containsEntry("finishReason", "tool_call")
                .containsKey("createdAt")
                .doesNotContainKeys("entry_id", "entry_seq", "finish_reason", "created_at");
        assertToolCall(event);
    }

    @Test
    void excludesFailedAssistantFromRestoredModelContext() {
        RuntimeEntryDTO user = entry("entry_user", "user.message", "{}");
        RuntimeEntryDTO failed = entry(
                "entry_failed",
                "assistant.message.completed",
                "{\"message\":{\"role\":\"assistant\",\"content\":[]},\"finish_reason\":\"error\"}");

        List<String> ids = new RuntimeEntryCodec(new ObjectMapper()).toAgentContextEntryIds(List.of(user, failed));

        assertThat(ids).containsExactly("entry_user");
    }

    @Test
    void restoresPersistedAssistantModelIdentityInsteadOfCurrentModel() {
        RuntimeEntryCodec codec = new RuntimeEntryCodec(new ObjectMapper());
        AssistantMessage original = new AssistantMessage(
                List.of(new TextContent("done")),
                "openai-responses",
                "openai",
                "old-model",
                null,
                Usage.empty(),
                StopReason.STOP,
                null,
                1L);
        RuntimeEntryDTO entry = codec.assistantEntry("session", "entry", original, OffsetDateTime.now());

        List<Message> restored = codec.toAgentMessages(List.of(entry), model());

        AssistantMessage assistant = (AssistantMessage) restored.getFirst();
        assertThat(assistant.api()).isEqualTo("openai-responses");
        assertThat(assistant.provider()).isEqualTo("openai");
        assertThat(assistant.model()).isEqualTo("old-model");
    }

    @Test
    void storesUsageOnlyInInternalRecordAndRestoresReasoningSignature() {
        RuntimeEntryCodec codec = new RuntimeEntryCodec(new ObjectMapper());
        Usage usage = new Usage(10, 5, 2, 1, 18, new Cost(0.1, 0.2, 0.01, 0.02, 0.33));
        AssistantMessage original = new AssistantMessage(
                List.of(new ThinkingContent("reason", "signature", false), new TextContent("done")),
                "openai-completions",
                "mate-model-manager",
                "managed-model",
                "response-1",
                usage,
                StopReason.STOP,
                null,
                1L);

        RuntimeEntryDTO entry = codec.assistantEntry("session", "entry", original, OffsetDateTime.now());
        RuntimeRecordDTO record = codec.usageRecord(
                "session",
                "record",
                "run",
                RuntimeUsageCause.ASSISTANT,
                "entry",
                1,
                StopReason.STOP,
                usage,
                OffsetDateTime.now());
        AssistantMessage restored = (AssistantMessage)
                codec.toAgentMessages(List.of(entry), model()).getFirst();

        assertThat(entry.getPayload()).contains("_thinking", "signature").doesNotContain("\"usage\"");
        assertThat(codec.toSseData(entry)).doesNotContainKey("usage");
        assertThat(record.getPayload()).contains("\"cause\":\"assistant\"", "\"totalTokens\":18");
        assertThat(restored.usage()).isEqualTo(Usage.empty());
        assertThat(((ThinkingContent) restored.content().getFirst()).thinkingSignature())
                .isEqualTo("signature");
    }

    @SuppressWarnings("unchecked")
    private static void assertToolCall(Map<String, Object> event) {
        Map<String, Object> message = (Map<String, Object>) event.get("message");
        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        Map<String, Object> toolCall = content.get(0);
        Map<String, Object> arguments = (Map<String, Object>) toolCall.get("arguments");
        assertThat(toolCall).containsEntry("toolCallId", "call_201").doesNotContainKey("tool_call_id");
        assertThat(arguments).containsEntry("uploaded_files", true).doesNotContainKey("uploadedFiles");
    }

    private static RuntimeEntryDTO assistantEntry() {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setId("entry_101");
        entry.setEntrySeq(19L);
        entry.setType("assistant.message.completed");
        entry.setTimestamp(OffsetDateTime.parse("2026-08-17T10:00:02Z"));
        entry.setPayload("{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_call\","
                + "\"tool_call_id\":\"call_201\",\"name\":\"query_abnormal_orders\","
                + "\"arguments\":{\"uploaded_files\":true}}]},\"finish_reason\":\"tool_call\"}");
        return entry;
    }

    private static RuntimeEntryDTO entry(String id, String type, String payload) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setId(id);
        entry.setType(type);
        entry.setPayload(payload);
        entry.setTimestamp(OffsetDateTime.parse("2026-08-17T10:00:02Z"));
        return entry;
    }

    private static Model model() {
        return new Model(
                "current-model",
                "Current",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                false,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                10_000,
                1_000,
                null,
                null,
                null);
    }
}
