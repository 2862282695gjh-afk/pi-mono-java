/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import com.huawei.hicampus.claw.codingagent.runtimeapi.web.json.UserMessageContentRequestDeserializer;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * user.message 有序内容块的请求联合类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@JsonDeserialize(using = UserMessageContentRequestDeserializer.class)
public sealed interface UserMessageContentRequestVO
        permits UserMessageContentRequestVO.TextRequestVO, UserMessageContentRequestVO.FileRequestVO {
    String getType();

    /**
     * user.message 的文本内容块。
     */
    @Data
    @JsonDeserialize(using = JsonDeserializer.None.class)
    final class TextRequestVO implements UserMessageContentRequestVO {
        @NotBlank
        @Size(max = ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS)
        private String text;

        @Override
        public String getType() {
            return "text";
        }

        @JsonSetter("type")
        public void readType(JsonNode value) {
            requireExact(value, "text");
        }

        @JsonSetter("text")
        public void readText(JsonNode value) {
            text = requireText(value);
        }

        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object value) {
            throw new IllegalArgumentException("unknown user message text field");
        }
    }

    /**
     * user.message 的文件引用内容块。
     */
    @Data
    @JsonDeserialize(using = JsonDeserializer.None.class)
    final class FileRequestVO implements UserMessageContentRequestVO {
        @NotBlank
        @Pattern(regexp = ClawConstants.RuntimeApi.EVENT_FILE_ID_REGEX)
        private String fileId;

        @Override
        public String getType() {
            return "file";
        }

        @JsonSetter("type")
        public void readType(JsonNode value) {
            requireExact(value, "file");
        }

        @JsonSetter("fileId")
        public void readFileId(JsonNode value) {
            fileId = requireText(value);
        }

        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object value) {
            throw new IllegalArgumentException("unknown user message file field");
        }
    }

    private static void requireExact(JsonNode value, String expected) {
        if (!expected.equals(requireText(value))) {
            throw new IllegalArgumentException("user message content type does not match fields");
        }
    }

    private static String requireText(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("user message content fields must be strings");
        }
        return value.textValue();
    }
}
