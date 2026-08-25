/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 定义模型是否可以在本次调用中选择函数工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public enum ToolChoice {
    AUTO("auto"),
    NONE("none");

    private final String value;

    ToolChoice(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ToolChoice fromValue(String value) {
        for (ToolChoice choice : values()) {
            if (choice.value.equals(value)) {
                return choice;
            }
        }
        throw new IllegalArgumentException("Unknown ToolChoice: " + value);
    }
}
