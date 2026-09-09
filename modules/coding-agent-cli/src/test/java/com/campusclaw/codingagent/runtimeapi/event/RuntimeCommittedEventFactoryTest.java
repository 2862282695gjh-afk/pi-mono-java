/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v2 权威事件构造与 data-only 编码测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCommittedEventFactoryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeCommittedEventFactory factory;

    private RuntimeV2EventEncoder encoder;

    @BeforeEach
    void setUp() {
        factory =
                new RuntimeCommittedEventFactory(objectMapper, new RuntimeMessageSourceConfiguration().messageSource());
        encoder = new RuntimeV2EventEncoder(new CommittedEventProjection(objectMapper), objectMapper);
    }

    @Test
    void shouldPreserveSafeReceiptContentOrderInDataOnlyFrame() {
        RuntimeEntryDTO entry = entry("event_user", "user.message");

        var frame = encoder.committed(factory.userMessage(
                entry, "/skill:pdf original arguments", List.of("0123456789abcdef0123456789abcdef")));

        assertThat(frame.isDataOnly()).isTrue();
        assertThat(frame.getId()).isNull();
        assertThat(frame.getData())
                .containsEntry("eventId", "event_user")
                .containsEntry("type", "user.message")
                .containsEntry("createdAt", "2026-09-08T01:02:03.456Z");
        assertThat(frame.getData().get("content"))
                .isEqualTo(List.of(
                        Map.of("type", "text", "text", "/skill:pdf original arguments"),
                        Map.of("type", "file", "fileId", "0123456789abcdef0123456789abcdef")));
    }

    @Test
    void shouldCreateCompleteControlReceipts() {
        var interrupt =
                encoder.committed(factory.userInterrupt(entry("event_interrupt", "user.interrupt"), "event_user"));
        var confirmation = encoder.committed(factory.userToolConfirmation(
                entry("event_confirmation", "user.tool_confirmation"), "call-1", "deny", "请先说明风险"));

        assertThat(interrupt.getData())
                .containsEntry("type", "user.interrupt")
                .containsEntry("targetEventId", "event_user");
        assertThat(confirmation.getData())
                .containsEntry("type", "user.tool_confirmation")
                .containsEntry("toolCallId", "call-1")
                .containsEntry("result", "deny")
                .containsEntry("denyMessage", "请先说明风险");
    }

    @Test
    void shouldExposeTextAndUsageWithoutPrivateThinking() {
        RuntimeEntryDTO entry = entry("entry_assistant", "assistant.message.completed");
        AssistantMessage message = new AssistantMessage(
                List.of(new ThinkingContent("private chain"), new TextContent("public answer")),
                "openai-responses",
                "openai",
                "gpt-test",
                null,
                new Usage(10, 5, 2, 1, 18, new Cost(0.1, 0.2, 0.03, 0.04, 0.37)),
                StopReason.STOP,
                null,
                1L);

        var frame = encoder.committed(factory.agentMessage(entry, "event_message", message, "event_user"));

        assertThat(frame.getData())
                .containsEntry("phase", "completed")
                .containsEntry("content", "public answer")
                .containsEntry("sourceEventId", "event_user");
        assertThat(frame.getData().toString()).doesNotContain("private chain");
        var usage = objectMapper.valueToTree(frame.getData().get("usage"));
        assertThat(usage.path("input").longValue()).isEqualTo(10L);
        assertThat(usage.path("output").longValue()).isEqualTo(5L);
        assertThat(usage.path("cacheRead").longValue()).isEqualTo(2L);
        assertThat(usage.path("cacheWrite").longValue()).isEqualTo(1L);
        assertThat(usage.path("totalTokens").longValue()).isEqualTo(18L);
        assertThat(usage.path("cost").path("total").doubleValue()).isEqualTo(0.37);
    }

    @Test
    void shouldKeepToolArgumentsAndFallbackToStableError() {
        RuntimeEntryDTO callEntry = entry("entry_call", "assistant.message.completed");
        ToolCall call = new ToolCall("call-1", "CallMateTool", Map.of("tool", "inspect"));
        var callFrame = encoder.committed(factory.agentToolCall(callEntry, "event_call", call, true, "event_user"));
        ToolResultMessage failed = new ToolResultMessage(
                "call-1", "CallMateTool", List.of(new TextContent("private gateway failure")), null, true, 1L);
        var resultFrame = encoder.committed(factory.agentToolResult(
                entry("entry_result", "tool.result"), "event_result", failed, "event_user", java.util.Locale.US));

        assertThat(callFrame.getData())
                .containsEntry("requiresConfirmation", true)
                .containsEntry("arguments", Map.of("tool", "inspect", "args", Map.of()));
        assertThat(resultFrame.getData())
                .containsEntry("errorCode", "TOOL_EXECUTION_FAILED")
                .containsEntry("isError", true)
                .containsEntry(
                        "content",
                        List.of(Map.of("type", "text", "text", "Tool execution failed. Check the execution outcome.")));
        assertThat(resultFrame.getData().toString()).doesNotContain("private gateway failure");
    }

    @Test
    void shouldOmitUnknownUsageAndUnknownCost() {
        var unknownUsage = encoder.committed(factory.agentMessage(
                entry("entry_unknown", "assistant.message.completed"),
                "event_unknown",
                assistant(Usage.empty()),
                "event_user"));
        Usage knownTokens = new Usage(4, 2, 0, 0, 6, Cost.empty());
        var unknownCost = encoder.committed(factory.agentMessage(
                entry("entry_tokens", "assistant.message.completed"),
                "event_tokens",
                assistant(knownTokens),
                "event_user"));
        Usage reportedZero = new Usage(0, 0, 0, 0, 0, Cost.empty());
        var knownZero = encoder.committed(factory.agentMessage(
                entry("entry_zero", "assistant.message.completed"),
                "event_zero",
                assistant(reportedZero),
                "event_user"));

        assertThat(unknownUsage.getData()).doesNotContainKey("usage");
        assertThat(objectMapper.valueToTree(unknownCost.getData().get("usage")).has("cost"))
                .isFalse();
        var zeroUsage = objectMapper.valueToTree(knownZero.getData().get("usage"));
        assertThat(zeroUsage.isObject()).isTrue();
        assertThat(zeroUsage.path("input").longValue()).isZero();
        assertThat(zeroUsage.path("output").longValue()).isZero();
        assertThat(zeroUsage.path("cacheRead").longValue()).isZero();
        assertThat(zeroUsage.path("cacheWrite").longValue()).isZero();
        assertThat(zeroUsage.path("totalTokens").longValue()).isZero();
    }

    @Test
    void shouldUseFixedIdleFailureMessage() {
        RuntimeEntryDTO entry = entry("event_idle", "session.status.idle");

        var frame = encoder.committed(factory.sessionIdle(
                entry, "event_idle", "failed", "event_user", "MODEL_REQUEST_FAILED", java.util.Locale.US));

        assertThat(frame.getData())
                .containsEntry("errorCode", "MODEL_REQUEST_FAILED")
                .containsEntry("message", "The model request failed.");
    }

    @Test
    void shouldTranslateInternalConfigurationTypesIntoPublicContract() {
        RuntimeEntryDTO model = entry("entry_model", "session.model.changed");
        model.setPayload("{\"previousModelId\":\"old\",\"modelId\":\"next\",\"reason\":\"agentRefresh\"}");
        RuntimeEntryDTO thinking = entry("entry_thinking", "session.thinking.changed");
        thinking.setPayload("{\"previousThinking\":true,\"thinking\":false,\"reason\":\"modelCapability\"}");

        var modelFrame = encoder.committed(factory.sessionConfiguration(model));
        var thinkingFrame = encoder.committed(factory.sessionConfiguration(thinking));

        assertThat(modelFrame.getData())
                .containsEntry("eventId", "entry_model")
                .containsEntry("type", "session.model_changed")
                .containsEntry("previousModelId", "old")
                .containsEntry("modelId", "next")
                .containsEntry("reason", "agentRefresh")
                .doesNotContainKey("entrySeq");
        assertThat(thinkingFrame.getData())
                .containsEntry("eventId", "entry_thinking")
                .containsEntry("type", "session.thinking_changed")
                .containsEntry("previousThinking", true)
                .containsEntry("thinking", false)
                .containsEntry("reason", "modelCapability")
                .doesNotContainKey("entrySeq");
    }

    private static AssistantMessage assistant(Usage usage) {
        return new AssistantMessage(
                List.of(new TextContent("answer")),
                "openai-responses",
                "openai",
                "gpt-test",
                null,
                usage,
                StopReason.STOP,
                null,
                1L);
    }

    private static RuntimeEntryDTO entry(String id, String type) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId("session-v2");
        entry.setId(id);
        entry.setType(type);
        entry.setTimestamp(OffsetDateTime.parse("2026-09-08T01:02:03.456789Z"));
        entry.setPayload("{}");
        return entry;
    }
}
