/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionSegmentState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;

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

    private RuntimeExecutionSegmentState state;

    private String terminalEventId;

    private Long terminalEventSeq;

    private RuntimeExecutionTerminalReason terminalReason;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
