/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 用户问题答案的锁内准入结果。
 *
 * @param status 准入状态
 * @param target 接受后新续跑段；拒绝时为空
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionAnswerAcceptanceDTO(Status status, ExecutionTargetDTO target) {
    /**
     * 用户问题答案准入的稳定分类。
     */
    public enum Status {
        ACCEPTED,
        SESSION_NOT_FOUND,
        NOT_PENDING,
        EXECUTION_STOPPING
    }
}
