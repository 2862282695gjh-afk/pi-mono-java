/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * 提交 user.message 事件的请求对象。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
public class UserEventRequestVO {
    public static final String USER_MESSAGE_TYPE_PATTERN = "^user\\.message$";

    @NotBlank
    @Pattern(regexp = USER_MESSAGE_TYPE_PATTERN)
    @Setter(AccessLevel.NONE)
    private String type;

    @Size(max = 262144)
    @Setter(AccessLevel.NONE)
    private String message;

    @Size(max = 32)
    @Setter(AccessLevel.NONE)
    private List<@NotBlank String> fileIds;

    /**
     * 仅接受 JSON 字符串类型的事件类型。
     *
     * @param value 原始 JSON 值
     */
    @JsonSetter("type")
    public void readType(JsonNode value) {
        type = readOptionalText(value, "type");
    }

    /**
     * 仅接受 JSON 字符串或 null 类型的消息正文。
     *
     * @param value 原始 JSON 值
     */
    @JsonSetter("message")
    public void readMessage(JsonNode value) {
        message = readOptionalText(value, "message");
    }

    /**
     * 仅接受由 JSON 字符串组成的 file_ids 数组。
     *
     * @param value 原始 JSON 值
     * @throws IllegalArgumentException 值不是数组或数组元素不是字符串时抛出
     */
    @JsonSetter("file_ids")
    public void readFileIds(JsonNode value) {
        if (value == null || value.isNull()) {
            fileIds = null;
            return;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("file_ids must be an array");
        }
        java.util.ArrayList<String> parsed = new java.util.ArrayList<>();
        value.forEach(item -> parsed.add(requireText(item, "file_ids item")));
        fileIds = List.copyOf(parsed);
    }

    /**
     * 拒绝契约未声明的请求字段。
     *
     * @param name 未知字段名
     * @param value 未知字段值
     * @throws IllegalArgumentException 始终抛出，阻止未知字段进入业务层
     */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown user event field: " + name);
    }

    private static String readOptionalText(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        return requireText(value, field);
    }

    private static String requireText(JsonNode value, String field) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }
}
