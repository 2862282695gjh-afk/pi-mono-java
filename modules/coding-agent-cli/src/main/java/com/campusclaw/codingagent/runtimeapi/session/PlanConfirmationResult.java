/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

/**
 * 用户对待审核计划作出的稳定决定。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public enum PlanConfirmationResult {
    APPROVE("approve"),
    REVISE("revise");

    private final String value;

    PlanConfirmationResult(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isApproved() {
        return this == APPROVE;
    }
}
