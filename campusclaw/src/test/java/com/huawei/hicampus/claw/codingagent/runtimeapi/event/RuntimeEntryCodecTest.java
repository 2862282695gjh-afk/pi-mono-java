/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.InputModality;
import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ModelCost;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ThinkingContent;
import com.huawei.hicampus.claw.ai.types.ToolResultMessage;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
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
    void shouldSizeDataOnlyFrameFromTheJsonActuallyWrittenToSse() throws Exception {
        RuntimeEntryCodec codec = codec();
        RuntimeSseEventVO event = RuntimeSseEventVO.dataOnly("user.message", Map.of("eventId", "事件一"));

        long encodedBytes = codec.encodedSseBytes(event);

        assertThat(encodedBytes).isEqualTo(new ObjectMapper().writeValueAsBytes(event.getData()).length);
        assertThat(encodedBytes).isLessThan(new ObjectMapper().writeValueAsBytes(event).length);
    }

    @Test
    void shouldKeepLegacyFrameSizingAndJsonFieldsUnchanged() throws Exception {
        RuntimeEntryCodec codec = codec();
        RuntimeSseEventVO event = new RuntimeSseEventVO("17", "user.message", Map.of("entryId", "entry-1"));
        ObjectMapper objectMapper = new ObjectMapper();

        long encodedBytes = codec.encodedSseBytes(event);

        assertThat(encodedBytes).isEqualTo(objectMapper.writeValueAsBytes(event).length);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(event)))
                .hasToString("{\"id\":\"17\",\"event\":\"user.message\",\"data\":{\"entryId\":\"entry-1\"}}");
    }

    @Test
    void storesToolFailureAsStableCodeWithoutInternalCategory() {
        RuntimeEntryCodec codec = codec();
        ToolResultMessage failure = new ToolResultMessage(
                "call_1",
                "querySkillInfo",
                List.of(new TextContent("MATE_RESPONSE_INVALID")),
                Map.of("errorCode", "MATE_RESPONSE_INVALID", "errorCategory", "InternalException"),
                true,
                1L);

        RuntimeEntryDTO entry = codec.toolResultEntry("session", "entry", failure, OffsetDateTime.now());

        assertThat(entry.getPayload())
                .contains("\"error_code\":\"MATE_RESPONSE_INVALID\"")
                .doesNotContain("error_category", "InternalException");
    }

    @Test
    void excludesFailedAssistantFromRestoredModelContext() {
        RuntimeEntryDTO user = entry("entry_user", "user.message", "{}");
        RuntimeEntryDTO failed = entry(
                "entry_failed",
                "assistant.message.completed",
                "{\"message\":{\"role\":\"assistant\",\"content\":[]},\"finish_reason\":\"error\"}");

        List<String> ids = codec().toAgentContextEntryIds(List.of(user, failed));

        assertThat(ids).containsExactly("entry_user");
    }

    @Test
    void restoresPersistedAssistantModelIdentityInsteadOfCurrentModel() {
        RuntimeEntryCodec codec = codec();
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
        RuntimeEntryCodec codec = codec();
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
        assertThat(codec.toSseData(entry, Locale.US)).doesNotContainKey("usage");
        assertThat(record.getPayload()).contains("\"cause\":\"assistant\"", "\"totalTokens\":18");
        assertThat(restored.usage()).isEqualTo(Usage.empty());
        assertThat(((ThinkingContent) restored.content().getFirst()).thinkingSignature())
                .isEqualTo("signature");
    }

    private static RuntimeEntryCodec codec() {
        return new RuntimeEntryCodec(
                new ObjectMapper(),
                new com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration().messageSource());
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
