/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 工具确认请求在执行行锁内的受理结果。
 *
 * @param status 锁内复核结果
 * @param target 接受后固定的新续跑段；拒绝时为空
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record ConfirmationAcceptanceDTO(Status status, ExecutionTargetDTO target) {
    /**
     * 工具确认受理的类型化结果。
     */
    public enum Status {
        ACCEPTED,
        SESSION_NOT_FOUND,
        NOT_PENDING,
        EXECUTION_STOPPING
    }
}
