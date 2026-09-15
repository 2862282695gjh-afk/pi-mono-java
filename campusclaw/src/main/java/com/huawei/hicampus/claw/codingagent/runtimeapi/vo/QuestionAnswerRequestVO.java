/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import java.util.List;

import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * user.question_answer 中单题答案的请求对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@JsonDeserialize(using = JsonDeserializer.None.class)
public class QuestionAnswerRequestVO {
    @NotBlank
    @Size(max = ClawConstants.RuntimeApi.MAX_QUESTION_ID_CHARACTERS)
    @Pattern(regexp = ClawConstants.RuntimeApi.QUESTION_ID_REGEX)
    private String id;

    @NotNull
    @Size(max = ClawConstants.RuntimeApi.MAX_QUESTION_OPTIONS)
    private List<@NotBlank @Size(max = ClawConstants.RuntimeApi.MAX_QUESTION_OPTION_LABEL_CHARACTERS) String> selected;

    @Size(max = ClawConstants.RuntimeApi.MAX_QUESTION_CUSTOM_ANSWER_CHARACTERS)
    private String custom;

    @JsonSetter("id")
    public void readId(JsonNode value) {
        id = requireText(value);
    }

    @JsonSetter("selected")
    public void readSelected(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("selected must be an array");
        }
        selected = new java.util.ArrayList<>();
        value.forEach(item -> selected.add(requireText(item)));
    }

    @JsonSetter("custom")
    public void readCustom(JsonNode value) {
        setCustom(requireText(value));
    }

    public void setCustom(String value) {
        custom = value == null ? null : value.strip();
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown question answer field");
    }

    private static String requireText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("question answer text field must be a string");
        }
        return value.textValue();
    }
}
