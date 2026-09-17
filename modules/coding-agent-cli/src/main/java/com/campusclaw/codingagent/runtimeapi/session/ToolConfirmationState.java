/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

/**
 * 工具确认、计划审核和问题答案的一次消费状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public enum ToolConfirmationState {
    PENDING,
    CLAIMED,
    COMPLETED,

    /**
     * 根执行已结束，尚未领取的决定不再消费；已提交的用户决定仍然保留。
     */
    CANCELED
}
