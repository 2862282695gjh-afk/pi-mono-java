/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 执行中断在锁内登记后的内部结果。
 *
 * @param status 锁内复核结果
 * @param target 接受时固定的执行目标；拒绝时为空
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record InterruptRequestDTO(Status status, ExecutionTargetDTO target) {
    /**
     * 中断登记的类型化结果。
     */
    public enum Status {
        ACCEPTED,
        SESSION_NOT_FOUND,
        SESSION_NOT_RUNNING,
        TARGET_MISMATCH,
        ALREADY_REQUESTED
    }
}
