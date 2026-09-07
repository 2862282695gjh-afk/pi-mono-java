/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 已选定 Builtin 类别后的请求约束，不承担共享入口的命令类别识别。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltinCommandRequestVO {
    @NotBlank
    @Size(max = ClawConstants.Skill.MAX_NAME_LENGTH)
    @Pattern(regexp = ClawConstants.Skill.NAME_REGEX)
    @Setter(AccessLevel.NONE)
    private String name;

    @Size(max = ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS)
    @Setter(AccessLevel.NONE)
    private String arguments;

    /**
     * 严格读取命令名称；必填和格式约束由 Jakarta 校验。
     *
     * @param value 原始 JSON 字段
     */
    @JsonSetter("name")
    public void readName(JsonNode value) {
        name = readText(value);
    }

    /**
     * 保留可选参数的原值与 null，不在请求对象中设置缺省值。
     *
     * @param value 原始 JSON 字段
     */
    @JsonSetter("arguments")
    public void readArguments(JsonNode value) {
        arguments = readText(value);
    }

    /**
     * 拒绝 Builtin 契约未声明的字段，包括附件；异常不携带输入内容。
     *
     * @param fieldName 未知字段名
     * @param value 未知字段值
     * @throws IllegalArgumentException 始终抛出以阻止未知字段
     */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown builtin command field");
    }

    private static String readText(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("builtin command fields must be strings");
        }
        return value.textValue();
    }
}
