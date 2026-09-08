/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 初始执行或确认续跑对应的固定结果段数据库状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ExecutionSegmentDTO {
    private String sessionId;

    private String executionId;

    private String segmentId;

    private int segmentOrdinal;

    private String triggerEventId;

    private long triggerEventSeq;

    private String state;

    private String terminalEventId;

    private Long terminalEventSeq;

    private String terminalReason;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
