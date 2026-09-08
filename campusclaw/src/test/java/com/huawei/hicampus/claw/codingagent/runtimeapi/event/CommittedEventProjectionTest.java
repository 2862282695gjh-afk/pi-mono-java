/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class CommittedEventProjectionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CommittedEventProjection projection = new CommittedEventProjection(objectMapper);

    @Test
    void shouldSerializeAgentMessageAsFlatCompleteEvent() throws Exception {
        String payload =
                """
                {"phase":"completed","content":"已完成","sourceEventId":"evt_root","usage":{
                  "input":12,"output":3,"cacheRead":2,"cacheWrite":1,"totalTokens":18,
                  "cost":{"input":0.1,"output":0.2,"cacheRead":0.01,"cacheWrite":0.02,"total":0.33}}}
                """;

        JsonNode actual = serialize(project("agent.message", payload));

        assertThat(actual.path("eventId").textValue()).isEqualTo("evt_public");
        assertThat(actual.path("createdAt").textValue()).isEqualTo("2026-09-08T01:02:03.456Z");
        assertThat(actual.path("phase").textValue()).isEqualTo("completed");
        assertThat(actual.path("usage").path("totalTokens").longValue()).isEqualTo(18L);
        assertThat(actual.path("usage").path("cost").path("total").decimalValue())
                .isEqualByComparingTo("0.33");
        assertThat(actual.has("details")).isFalse();
        assertThat(actual.has("eventSeq")).isFalse();
        assertThat(actual.has("anchorEntryId")).isFalse();
    }

    @Test
    void shouldSerializeEveryCompleteTypeWithExactBusinessFields() {
        Map<String, String> payloads = completePayloads();
        Map<String, Set<String>> businessFields = completeBusinessFields();

        assertThat(payloads).hasSameSizeAs(businessFields).hasSize(11);
        payloads.forEach((type, payload) -> assertExactFields(type, payload, businessFields.get(type)));
    }

    @Test
    void shouldKeepPersistedFailureTextWithoutTranslation() throws Exception {
        String payload =
                """
                {"reason":"failed","sourceEventId":"evt_root","errorCode":"MODEL_REQUEST_FAILED",
                 "message":"The saved execution message."}
                """;

        JsonNode actual = serialize(project("session.status_idle", payload));

        assertThat(actual.path("message").textValue()).isEqualTo("The saved execution message.");
        assertThat(actual.path("errorCode").textValue()).isEqualTo("MODEL_REQUEST_FAILED");
    }

    @Test
    void shouldSerializeToolErrorWithExactBooleanName() throws Exception {
        String payload =
                """
                {"toolCallId":"call_1","content":[{"type":"text","text":"拒绝执行"}],"isError":true,
                 "errorCode":"TOOL_CALL_DENIED","sourceEventId":"evt_root"}
                """;

        JsonNode actual = serialize(project("agent.tool_result", payload));

        assertThat(actual.path("isError").booleanValue()).isTrue();
        assertThat(actual.has("error")).isFalse();
        assertThat(actual.path("errorCode").textValue()).isEqualTo("TOOL_CALL_DENIED");
    }

    @Test
    void shouldOmitCompleteOnlyFieldsFromDelta() throws Exception {
        var details = new SessionEventResponseVO.AgentMessageResponseVO("delta", "新增", "evt_root", null);
        var delta = new SessionEventResponseVO("evt_delta", "agent.message", null, details);

        JsonNode actual = serialize(delta);

        assertThat(actual.has("createdAt")).isFalse();
        assertThat(actual.has("usage")).isFalse();
        assertThat(actual.path("phase").textValue()).isEqualTo("delta");
    }

    @Test
    void shouldRejectUnknownOrUnsafePersistedEvents() {
        RuntimeApiException unknown = assertThrows(RuntimeApiException.class, () -> project("future.event", "{}"));
        RuntimeApiException missingCode = assertThrows(
                RuntimeApiException.class,
                () -> project(
                        "agent.tool_result",
                        "{\"toolCallId\":\"call_1\",\"content\":[{\"type\":\"text\",\"text\":\"failed\"}],"
                                + "\"isError\":true,\"sourceEventId\":\"evt_root\"}"));
        RuntimeApiException delta = assertThrows(
                RuntimeApiException.class,
                () -> project(
                        "agent.thinking",
                        "{\"phase\":\"delta\",\"content\":\"private\",\"sourceEventId\":\"evt_root\"}"));

        assertThat(unknown.errorCode()).isEqualTo(RuntimeErrorCode.EVENT_LIST_FAILED);
        assertThat(missingCode.errorCode()).isEqualTo(RuntimeErrorCode.EVENT_LIST_FAILED);
        assertThat(delta.errorCode()).isEqualTo(RuntimeErrorCode.EVENT_LIST_FAILED);
    }

    @Test
    void shouldReturnImmutableToolArguments() {
        String payload =
                """
                {"toolCallId":"call_1","toolName":"CallMateTool","arguments":{"tool":"query","args":{"x":1}},
                 "requiresConfirmation":true,"sourceEventId":"evt_root"}
                """;
        var details = (SessionEventResponseVO.AgentToolCallResponseVO)
                project("agent.tool_call", payload).getDetails();

        assertThrows(UnsupportedOperationException.class, () -> details.getArguments()
                .put("new", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) details.getArguments().get("args");
        assertThrows(UnsupportedOperationException.class, () -> args.put("new", true));
    }

    @Test
    void shouldRejectInvalidPublicUserContentAndDenyMessage() {
        String fiveFiles = "{\"content\":["
                + "{\"type\":\"file\",\"fileId\":\"00000000000000000000000000000000\"},"
                + "{\"type\":\"file\",\"fileId\":\"00000000000000000000000000000001\"},"
                + "{\"type\":\"file\",\"fileId\":\"00000000000000000000000000000002\"},"
                + "{\"type\":\"file\",\"fileId\":\"00000000000000000000000000000003\"},"
                + "{\"type\":\"file\",\"fileId\":\"00000000000000000000000000000004\"}]}";
        String longDenial =
                "{\"toolCallId\":\"call_1\",\"result\":\"deny\",\"denyMessage\":\"" + "x".repeat(4097) + "\"}";

        assertThrows(
                RuntimeApiException.class,
                () -> project("user.message", "{\"content\":[{\"type\":\"text\",\"text\":\" \\t\"}]}"));
        assertThrows(RuntimeApiException.class, () -> project("user.message", fiveFiles));
        assertThrows(
                RuntimeApiException.class,
                () -> project("user.message", "{\"content\":[{\"type\":\"file\",\"fileId\":\"bad\"}]}"));
        assertThrows(RuntimeApiException.class, () -> project("user.tool_confirmation", longDenial));
    }

    private SessionEventResponseVO project(String type, String payload) {
        CommittedEventDTO event = new CommittedEventDTO();
        event.setSessionId("ses_1");
        event.setEventId("evt_public");
        event.setEventSeq(8L);
        event.setAnchorEntryId("entry_1");
        event.setType(type);
        event.setCreatedAt(OffsetDateTime.parse("2026-09-08T09:02:03.456+08:00"));
        event.setPayload(payload);
        return projection.project(event);
    }

    private JsonNode serialize(SessionEventResponseVO event) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(event));
    }

    private Map<String, String> completePayloads() {
        return Map.ofEntries(
                Map.entry(
                        "user.message",
                        "{\"content\":[{\"type\":\"text\",\"text\":\"你好\"},{\"type\":\"file\","
                                + "\"fileId\":\"7f12d8a380964fdc8518f48be1cf6a42\"}]}"),
                Map.entry("user.interrupt", "{\"targetEventId\":\"evt_root\"}"),
                Map.entry(
                        "user.tool_confirmation",
                        "{\"toolCallId\":\"call_1\",\"result\":\"deny\",\"denyMessage\":\"不要执行\"}"),
                Map.entry("agent.message", "{\"phase\":\"completed\",\"content\":\"\",\"sourceEventId\":\"evt_root\"}"),
                Map.entry(
                        "agent.thinking",
                        "{\"phase\":\"completed\",\"content\":\"摘要\",\"sourceEventId\":\"evt_root\"}"),
                Map.entry(
                        "agent.tool_call",
                        "{\"toolCallId\":\"call_1\",\"toolName\":\"Read\",\"arguments\":{\"path\":\"/tmp/a\"},"
                                + "\"requiresConfirmation\":false,\"sourceEventId\":\"evt_root\"}"),
                Map.entry(
                        "agent.tool_result",
                        "{\"toolCallId\":\"call_1\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                                + "\"isError\":false,\"sourceEventId\":\"evt_root\"}"),
                Map.entry("session.status_idle", "{\"reason\":\"done\",\"sourceEventId\":\"evt_root\"}"),
                Map.entry(
                        "session.model_changed",
                        "{\"previousModelId\":\"old\",\"modelId\":\"new\",\"reason\":\"requested\"}"),
                Map.entry(
                        "session.thinking_changed",
                        "{\"previousThinking\":true,\"thinking\":false,\"reason\":\"modelCapability\"}"),
                Map.entry(
                        "session.compacted",
                        "{\"reason\":\"threshold\",\"tokensBefore\":10,\"estimatedTokensAfter\":4,"
                                + "\"sourceEventId\":\"evt_root\"}"));
    }

    private Map<String, Set<String>> completeBusinessFields() {
        return Map.ofEntries(
                Map.entry("user.message", Set.of("content")),
                Map.entry("user.interrupt", Set.of("targetEventId")),
                Map.entry("user.tool_confirmation", Set.of("toolCallId", "result", "denyMessage")),
                Map.entry("agent.message", Set.of("phase", "content", "sourceEventId")),
                Map.entry("agent.thinking", Set.of("phase", "content", "sourceEventId")),
                Map.entry(
                        "agent.tool_call",
                        Set.of("toolCallId", "toolName", "arguments", "requiresConfirmation", "sourceEventId")),
                Map.entry("agent.tool_result", Set.of("toolCallId", "content", "isError", "sourceEventId")),
                Map.entry("session.status_idle", Set.of("reason", "sourceEventId")),
                Map.entry("session.model_changed", Set.of("previousModelId", "modelId", "reason")),
                Map.entry("session.thinking_changed", Set.of("previousThinking", "thinking", "reason")),
                Map.entry(
                        "session.compacted",
                        Set.of("reason", "tokensBefore", "estimatedTokensAfter", "sourceEventId")));
    }

    private void assertExactFields(String type, String payload, Set<String> businessFields) {
        JsonNode actual = objectMapper.valueToTree(project(type, payload));
        Set<String> expected = new HashSet<>(Set.of("eventId", "type", "createdAt"));
        expected.addAll(businessFields);
        Set<String> actualFields = new HashSet<>();
        actual.fieldNames().forEachRemaining(actualFields::add);
        assertThat(actualFields).isEqualTo(expected);
        assertThat(actual.path("type").textValue()).isEqualTo(type);
    }
}
