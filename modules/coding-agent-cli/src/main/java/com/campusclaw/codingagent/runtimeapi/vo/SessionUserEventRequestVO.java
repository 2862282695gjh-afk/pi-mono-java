/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

/**
 * Session Events v2 支持的三种用户事件请求联合类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public sealed interface SessionUserEventRequestVO
        permits UserMessageEventRequestVO, UserInterruptEventRequestVO, UserToolConfirmationEventRequestVO {
    String getType();
}
