/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.ArrayList;
import java.util.List;

import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

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
 * Skill 命令的原始请求，仅定义字段类型和边界约束，不归一化可选输入。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(using = JsonDeserializer.None.class)
public final class SkillCommandRequestVO implements CommandRequestVO {
    @NotBlank
    @Size(max = ClawConstants.Skill.MAX_COMMAND_NAME_LENGTH)
    @Pattern(regexp = ClawConstants.Skill.COMMAND_NAME_REGEX)
    @Setter(AccessLevel.NONE)
    private String name;

    @Size(max = ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS)
    @Setter(AccessLevel.NONE)
    private String arguments;

    @Size(max = ClawConstants.RuntimeApi.MAX_EVENT_FILE_IDS)
    @Setter(AccessLevel.NONE)
    private List<@NotBlank @Pattern(regexp = ClawConstants.RuntimeApi.EVENT_FILE_ID_REGEX) String> fileIds;

    @JsonSetter("name")
    public void readName(JsonNode value) {
        name = readOptionalText(value);
    }

    @JsonSetter("arguments")
    public void readArguments(JsonNode value) {
        arguments = readOptionalText(value);
    }

    @JsonSetter("fileIds")
    public void readFileIds(JsonNode value) {
        if (value == null || value.isNull()) {
            fileIds = null;
            return;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("skill command fileIds must be an array");
        }
        var parsed = new ArrayList<String>();
        value.forEach(item -> parsed.add(requireText(item)));
        fileIds = List.copyOf(parsed);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown skill command field");
    }

    private static String readOptionalText(JsonNode value) {
        return value == null || value.isNull() ? null : requireText(value);
    }

    private static String requireText(JsonNode value) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException("skill command fields must be strings");
        }
        return value.textValue();
    }
}
