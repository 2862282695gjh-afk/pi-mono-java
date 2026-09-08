/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import com.huawei.hicampus.claw.codingagent.runtimeapi.event.CommittedEventType;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * user.interrupt 的固定原消息目标请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@JsonDeserialize(using = JsonDeserializer.None.class)
public final class UserInterruptEventRequestVO implements SessionUserEventRequestVO {
    @NotBlank
    private String targetEventId;

    @Override
    public String getType() {
        return CommittedEventType.USER_INTERRUPT.value();
    }

    @JsonSetter("type")
    public void readType(JsonNode value) {
        requireExactType(value, getType());
    }

    @JsonSetter("targetEventId")
    public void readTargetEventId(JsonNode value) {
        targetEventId = requireText(value);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown user.interrupt field");
    }

    private static void requireExactType(JsonNode value, String expected) {
        if (!expected.equals(requireText(value))) {
            throw new IllegalArgumentException("user event type does not match request fields");
        }
    }

    private static String requireText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("user.interrupt fields must be strings");
        }
        return value.textValue();
    }
}
