/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

/**
 * Runtime Session 的持久化运行状态。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public enum RuntimeSessionState {
    IDLE("idle"),
    RUNNING("running");

    private final String value;

    RuntimeSessionState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean matches(String candidate) {
        return value.equals(candidate);
    }
}
