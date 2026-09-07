/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * Session 配置原子更新的结果。
 *
 * @param status 更新状态
 * @param session 可见的 Session 当前值；不存在时为 null
 * @param sourceEventSeq 本次追加的最后一条领域 Entry 序号；无追加时为 null
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionConfigurationUpdateDTO(Status status, RuntimeSessionDTO session, Long sourceEventSeq) {
    public SessionConfigurationUpdateDTO(Status status, RuntimeSessionDTO session) {
        this(status, session, null);
    }

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
