/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

/**
 * Runtime 内部 Usage Record 的产生原因。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public enum RuntimeUsageCause {
    ASSISTANT("assistant"),
    COMPACTION("compaction"),
    BRANCH_SUMMARY("branch_summary"),
    DEFERRED_FETCH("deferred_fetch");

    private final String value;

    RuntimeUsageCause(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
