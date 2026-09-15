/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * user.plan_confirmation 的计划审核决定请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@JsonDeserialize(using = JsonDeserializer.None.class)
public final class UserPlanConfirmationEventRequestVO implements SessionUserEventRequestVO {
    @NotBlank
    @Size(max = 128)
    private String targetEventId;

    @NotBlank
    @Pattern(regexp = ClawConstants.RuntimeApi.PLAN_CONFIRMATION_RESULT_REGEX)
    private String result;

    @Size(max = ClawConstants.RuntimeApi.MAX_PLAN_FEEDBACK_CHARACTERS)
    private String feedback;

    @JsonIgnore
    private boolean feedbackPresent;

    @Override
    public String getType() {
        return CommittedEventType.USER_PLAN_CONFIRMATION.value();
    }

    @JsonSetter("type")
    public void readType(JsonNode value) {
        if (!getType().equals(requireText(value))) {
            throw new IllegalArgumentException("user event type does not match request fields");
        }
    }

    @JsonSetter("targetEventId")
    public void readTargetEventId(JsonNode value) {
        targetEventId = requireText(value);
    }

    @JsonSetter("result")
    public void readResult(JsonNode value) {
        result = requireText(value);
    }

    @JsonSetter("feedback")
    public void readFeedback(JsonNode value) {
        feedbackPresent = true;
        feedback = requireText(value);
    }

    @AssertTrue
    @JsonIgnore
    public boolean isFeedbackValid() {
        if ("approve".equals(result)) {
            return !feedbackPresent;
        }
        return !feedbackPresent || (feedback != null && !feedback.isBlank());
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown user.plan_confirmation field");
    }

    private static String requireText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("plan confirmation fields must be strings");
        }
        return value.textValue();
    }
}
