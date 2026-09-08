/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

/**
 * 固定执行或 HTTP 结果段的结束原因。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public enum RuntimeExecutionTerminalReason {
    DONE("done", true),
    FAILED("failed", true),
    TERMINATED("terminated", true),
    CONFIRMING("confirming", false);

    private final String value;

    private final boolean executionTerminal;

    RuntimeExecutionTerminalReason(String value, boolean executionTerminal) {
        this.value = value;
        this.executionTerminal = executionTerminal;
    }

    public String value() {
        return value;
    }

    public boolean executionTerminal() {
        return executionTerminal;
    }
}
