/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

/**
 * 工具确认的持久化决定。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public enum ToolConfirmationResult {
    ALLOW("allow"),
    DENY("deny");

    private final String value;

    ToolConfirmationResult(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
