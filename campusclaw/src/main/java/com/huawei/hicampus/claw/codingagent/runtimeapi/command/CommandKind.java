/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 命令描述符的来源类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
public enum CommandKind {
    BUILTIN("builtin"),
    SKILL("skill");

    private final String value;

    CommandKind(String value) {
        this.value = value;
    }

    /**
     * 返回描述符使用的小写枚举值。
     *
     * @return 小写枚举值
     */
    @JsonValue
    public String value() {
        return value;
    }
}
