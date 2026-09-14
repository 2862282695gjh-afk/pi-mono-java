/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * Session 名称、模型、思考和模式的统一部分更新请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class UpdateSessionRequestVO {
    @Setter(AccessLevel.NONE)
    private String displayName;

    @Pattern(regexp = ClawConstants.RuntimeApi.MODEL_ID_REGEX)
    @Setter(AccessLevel.NONE)
    private String modelId;

    @Setter(AccessLevel.NONE)
    private Boolean thinking;

    @Pattern(regexp = ClawConstants.Agent.MODE_REGEX)
    @Setter(AccessLevel.NONE)
    private String mode;

    @JsonIgnore
    private boolean displayNameSubmitted;

    @JsonIgnore
    private boolean modelIdSubmitted;

    @JsonIgnore
    private boolean thinkingSubmitted;

    @JsonIgnore
    private boolean modeSubmitted;

    @JsonSetter("displayName")
    public void readDisplayName(JsonNode value) {
        requireFirst(displayNameSubmitted, "displayName");
        displayName = requireText(value, "displayName");
        displayNameSubmitted = true;
    }

    @JsonSetter("modelId")
    public void readModelId(JsonNode value) {
        requireFirst(modelIdSubmitted, "modelId");
        modelId = requireText(value, "modelId");
        modelIdSubmitted = true;
    }

    @JsonSetter("thinking")
    public void readThinking(JsonNode value) {
        requireFirst(thinkingSubmitted, "thinking");
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("thinking must be a boolean");
        }
        thinking = value.booleanValue();
        thinkingSubmitted = true;
    }

    @JsonSetter("mode")
    public void readMode(JsonNode value) {
        requireFirst(modeSubmitted, "mode");
        mode = requireText(value, "mode");
        modeSubmitted = true;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown Session update field: " + name);
    }

    @AssertTrue
    @JsonIgnore
    public boolean isNonEmptyUpdate() {
        return displayNameSubmitted || modelIdSubmitted || thinkingSubmitted || modeSubmitted;
    }

    @JsonIgnore
    public boolean hasExecutionConfiguration() {
        return modelIdSubmitted || thinkingSubmitted || modeSubmitted;
    }

    private static String requireText(JsonNode value, String field) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }

    private static void requireFirst(boolean submitted, String field) {
        if (submitted) {
            throw new IllegalArgumentException("duplicate Session update field: " + field);
        }
    }
}
