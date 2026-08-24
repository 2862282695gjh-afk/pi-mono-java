/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
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
}
