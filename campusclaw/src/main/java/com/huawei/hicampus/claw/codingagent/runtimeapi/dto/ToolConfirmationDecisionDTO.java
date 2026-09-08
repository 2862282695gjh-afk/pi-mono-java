/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationState;

import lombok.Data;

/**
 * 执行实例一次消费的工具确认决定。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ToolConfirmationDecisionDTO {
    private String sessionId;

    private String executionId;

    private String confirmationEventId;

    private String previousSegmentId;

    private String segmentId;

    private String toolCallId;

    private ToolConfirmationResult result;

    private String denyMessage;

    private ToolConfirmationState state;

    private OffsetDateTime createdAt;

    private OffsetDateTime claimedAt;

    private OffsetDateTime completedAt;
}
