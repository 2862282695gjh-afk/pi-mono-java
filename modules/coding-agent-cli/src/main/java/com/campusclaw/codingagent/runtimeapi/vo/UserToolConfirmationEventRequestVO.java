/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.Set;

import com.campusclaw.codingagent.runtimeapi.event.CommittedEventType;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * user.tool_confirmation 的待确认工具决定请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@JsonDeserialize(using = JsonDeserializer.None.class)
public final class UserToolConfirmationEventRequestVO implements SessionUserEventRequestVO {
    private static final Set<String> RESULTS = Set.of("allow", "deny");

    @NotBlank
    private String toolCallId;

    @NotBlank
    private String result;

    @Size(max = ClawConstants.RuntimeApi.MAX_DENY_MESSAGE_CHARACTERS)
    private String denyMessage;

    @Override
    public String getType() {
        return CommittedEventType.USER_TOOL_CONFIRMATION.value();
    }

    @JsonSetter("type")
    public void readType(JsonNode value) {
        requireExactType(value, getType());
    }

    @JsonSetter("toolCallId")
    public void readToolCallId(JsonNode value) {
        toolCallId = requireText(value);
    }

    @JsonSetter("result")
    public void readResult(JsonNode value) {
        result = requireText(value);
        if (!RESULTS.contains(result)) {
            throw new IllegalArgumentException("tool confirmation result is unsupported");
        }
    }

    @JsonSetter("denyMessage")
    public void readDenyMessage(JsonNode value) {
        denyMessage = requireText(value);
    }

    @AssertTrue
    @JsonIgnore
    public boolean isDenyMessageValid() {
        if ("allow".equals(result)) {
            return denyMessage == null;
        }
        return denyMessage == null || !denyMessage.isBlank();
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown user.tool_confirmation field");
    }

    private static void requireExactType(JsonNode value, String expected) {
        if (!expected.equals(requireText(value))) {
            throw new IllegalArgumentException("user event type does not match request fields");
        }
    }

    private static String requireText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("tool confirmation fields must be strings");
        }
        return value.textValue();
    }
}
