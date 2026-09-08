/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SessionEventResponseVOTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeToolResultWithExactIsErrorProperty() {
        var success = toolResult(false, null);
        var failure = toolResult(true, "TOOL_CALL_DENIED");

        JsonNode successJson = objectMapper.valueToTree(success);
        JsonNode failureJson = objectMapper.valueToTree(failure);

        assertThat(successJson.path("isError").booleanValue()).isFalse();
        assertThat(successJson.has("error")).isFalse();
        assertThat(successJson.has("errorCode")).isFalse();
        assertThat(failureJson.path("isError").booleanValue()).isTrue();
        assertThat(failureJson.path("errorCode").textValue()).isEqualTo("TOOL_CALL_DENIED");
        assertThat(failureJson.has("error")).isFalse();
    }

    @Test
    void shouldOmitCompleteOnlyFieldsFromDelta() {
        var details = new SessionEventResponseVO.AgentMessageResponseVO("delta", "新增", "evt_root", null);
        var delta = new SessionEventResponseVO("evt_delta", "agent.message", null, details);

        JsonNode actual = objectMapper.valueToTree(delta);

        assertThat(actual.has("createdAt")).isFalse();
        assertThat(actual.has("usage")).isFalse();
        assertThat(actual.has("details")).isFalse();
        assertThat(actual.path("phase").textValue()).isEqualTo("delta");
    }

    @Test
    void shouldSerializeCompletedEventAsFlatObject() {
        var details = new SessionEventResponseVO.UserInterruptResponseVO("evt_root");
        var completed =
                new SessionEventResponseVO("evt_interrupt", "user.interrupt", "2026-09-08T01:02:03.456Z", details);

        JsonNode actual = objectMapper.valueToTree(completed);

        assertThat(actual.path("createdAt").textValue()).isEqualTo("2026-09-08T01:02:03.456Z");
        assertThat(actual.path("targetEventId").textValue()).isEqualTo("evt_root");
        assertThat(actual.has("details")).isFalse();
    }

    private SessionEventResponseVO toolResult(boolean isError, String errorCode) {
        var content = List.of(new SessionEventResponseVO.TextContentResponseVO("text", "result"));
        var details =
                new SessionEventResponseVO.AgentToolResultResponseVO("call_1", content, isError, errorCode, "evt_root");
        return new SessionEventResponseVO("evt_result", "agent.tool_result", "2026-09-08T01:02:03.456Z", details);
    }
}
