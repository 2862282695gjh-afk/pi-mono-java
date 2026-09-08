/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 一轮消息执行的数据库状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ExecutionStateDTO {
    private String sessionId;

    private String executionId;

    private String rootEventId;

    private String state;

    private String currentSegmentId;

    private String pendingToolCallId;

    private String stopEventId;

    private String terminalEventId;

    private String terminalReason;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime terminalAt;
}
