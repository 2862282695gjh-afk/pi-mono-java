/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

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
    private String type;

    @Size(max = 262144)
    private String message;

    @Size(max = 32)
    @JsonProperty("file_ids")
    private List<@NotBlank String> fileIds;

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
}
