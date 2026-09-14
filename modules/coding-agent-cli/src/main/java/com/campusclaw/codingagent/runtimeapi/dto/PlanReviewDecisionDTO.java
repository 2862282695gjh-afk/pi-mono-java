/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import com.campusclaw.codingagent.runtimeapi.session.PlanConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationState;

import lombok.Data;

/**
 * 原执行实例一次消费的计划审核决定。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class PlanReviewDecisionDTO {
    private String sessionId;

    private String executionId;

    private String confirmationEventId;

    private String targetEventId;

    private String previousSegmentId;

    private String segmentId;

    private String toolCallId;

    private PlanConfirmationResult result;

    private String feedback;

    private ToolConfirmationState state;

    private OffsetDateTime createdAt;

    private OffsetDateTime claimedAt;

    private OffsetDateTime completedAt;
}
