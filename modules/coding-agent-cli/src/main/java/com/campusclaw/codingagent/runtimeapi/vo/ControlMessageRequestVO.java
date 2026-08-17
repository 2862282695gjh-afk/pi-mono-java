/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * Steering Message 与 FollowUp Message 的请求 VO。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
public class ControlMessageRequestVO {
    @NotBlank
    @Size(max = 262144)
    @Setter(AccessLevel.NONE)
    private String message;

    /**
     * 仅接受 JSON 字符串类型的 message。
     *
     * @param value 原始 JSON 值
     * @throws IllegalArgumentException 字段值不是 JSON 字符串时抛出
     */
    @JsonSetter("message")
    public void readMessage(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("message must be a string");
        }
        message = value.textValue();
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
        throw new IllegalArgumentException("unknown control message field: " + name);
    }
}
