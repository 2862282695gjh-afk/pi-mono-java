/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * user.message 持久化接收事务的结果。
 *
 * @param status 接收状态
 * @param session 接收时锁定的 Session 快照；不存在时为空
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record UserEventAcceptance(Status status, RuntimeSessionDTO session) {
    /**
     * user.message 接收事务的稳定结果类型。
     */
    public enum Status {
        ACCEPTED,
        NOT_FOUND,
        BUSY
    }
}
