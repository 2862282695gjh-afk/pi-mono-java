/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * Session 配置原子更新的结果。
 *
 * @param status 更新状态
 * @param session 可见的 Session 当前值；不存在时为 null
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionConfigurationUpdate(Status status, RuntimeSessionDTO session) {
    /**
     * Session 配置更新的稳定状态。
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/18]
     * @since [br_eCampusCore 26.0.0]
     */
    public enum Status {
        UPDATED,
        UNCHANGED,
        NOT_FOUND,
        BUSY,
        VERSION_MISMATCH
    }
}
