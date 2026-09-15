/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.event.CommittedEventType;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * user.question_answer 的完整批量答案请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@JsonDeserialize(using = JsonDeserializer.None.class)
public final class UserQuestionAnswerEventRequestVO implements SessionUserEventRequestVO {
    @NotBlank
    @Size(max = 128)
    private String targetEventId;

    @Valid
    @NotNull
    @Size(min = 1, max = ClawConstants.RuntimeApi.MAX_QUESTIONS)
    private List<@Valid QuestionAnswerRequestVO> answers;

    @Override
    public String getType() {
        return CommittedEventType.USER_QUESTION_ANSWER.value();
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

    @JsonSetter("answers")
    public void readAnswers(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("answers must be an array");
        }
        answers = new java.util.ArrayList<>();
        value.forEach(item -> answers.add(convert(item)));
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown user.question_answer field");
    }

    private static QuestionAnswerRequestVO convert(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("question answer must be an object");
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        java.util.Set<String> required = java.util.Set.of("id", "selected");
        if (!names.containsAll(required)
                || !java.util.Set.of("id", "selected", "custom").containsAll(names)) {
            throw new IllegalArgumentException("question answer fields are invalid");
        }
        var answer = new QuestionAnswerRequestVO();
        answer.readId(node.get("id"));
        answer.readSelected(node.get("selected"));
        if (node.has("custom")) {
            answer.readCustom(node.get("custom"));
        }
        return answer;
    }

    private static String requireText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("question answer event fields must have the required type");
        }
        return value.textValue();
    }
}
