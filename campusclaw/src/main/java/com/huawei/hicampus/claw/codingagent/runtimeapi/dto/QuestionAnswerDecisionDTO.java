/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationState;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;

import lombok.Data;

/**
 * 原执行实例一次消费的用户问题答案交接记录。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class QuestionAnswerDecisionDTO {
    private String sessionId;

    private String executionId;

    private String answerEventId;

    private String targetEventId;

    private String previousSegmentId;

    private String segmentId;

    private String toolCallId;

    private ToolConfirmationState state;

    private OffsetDateTime createdAt;

    private OffsetDateTime claimedAt;

    private OffsetDateTime completedAt;

    private String answerPayload;

    private QuestionAnswerResultDTO answers;
}
