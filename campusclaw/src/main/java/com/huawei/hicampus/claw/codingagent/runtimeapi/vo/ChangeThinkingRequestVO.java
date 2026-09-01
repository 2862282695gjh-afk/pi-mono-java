/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * Session 深度思考开关修改请求 VO。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ChangeThinkingRequestVO {
    @NotNull
    @Setter(AccessLevel.NONE)
    private Boolean thinking;

    /**
     * 仅接受 JSON 布尔类型的 thinking。
     *
     * @param value 原始 JSON 值
     * @throws IllegalArgumentException 字段值不是 JSON 布尔值时抛出
     */
    @JsonSetter("thinking")
    public void readThinking(JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("thinking must be a boolean");
        }
        thinking = value.booleanValue();
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
        throw new IllegalArgumentException("unknown thinking change field: " + name);
    }
}
