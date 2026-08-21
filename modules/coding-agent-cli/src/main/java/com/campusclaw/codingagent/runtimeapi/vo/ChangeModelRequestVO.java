/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * Session 默认模型切换请求 VO。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ChangeModelRequestVO {
    @NotBlank
    @Pattern(regexp = RuntimeApiConstants.MODEL_ID_PATTERN)
    @Setter(AccessLevel.NONE)
    private String modelId;

    /**
     * 仅接受 JSON 字符串类型的 modelId。
     *
     * @param value 原始 JSON 值
     * @throws IllegalArgumentException 字段值不是 JSON 字符串时抛出
     */
    @JsonSetter("modelId")
    public void readModelId(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("modelId must be a string");
        }
        modelId = value.textValue();
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
        throw new IllegalArgumentException("unknown model change field: " + name);
    }
}
