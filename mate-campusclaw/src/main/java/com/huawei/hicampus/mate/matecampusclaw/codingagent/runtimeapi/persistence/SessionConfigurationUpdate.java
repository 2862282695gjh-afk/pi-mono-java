/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * Session 配置原子更新的结果。
 *
 * @param status 更新状态
 * @param session 可见的 Session 当前值；不存在时为 null
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record SessionConfigurationUpdate(Status status, RuntimeSessionDTO session) {
    /**
     * Session 配置更新的稳定状态。
     *
     * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
     * @since [br_eCampusCore 25.1.0_Next]
     */
    public enum Status {
        UPDATED,
        UNCHANGED,
        NOT_FOUND,
        FORBIDDEN,
        BUSY,
        VERSION_MISMATCH
    }
}
