/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

/**
 * 压缩在数据库行锁内复核后的准入状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public enum CompactionAcceptanceStatus {
    ACCEPTED,
    NOT_FOUND,
    BUSY
}
