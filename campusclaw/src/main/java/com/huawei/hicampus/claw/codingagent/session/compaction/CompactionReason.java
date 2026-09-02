/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session.compaction;

/**
 * Session 上下文压缩的触发原因。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public enum CompactionReason {
    THRESHOLD("threshold"),
    OVERFLOW("overflow"),
    MANUAL("manual");

    private final String value;

    CompactionReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
