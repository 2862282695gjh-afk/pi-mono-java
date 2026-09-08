/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import lombok.Data;

/**
 * 数据库中已提交且需要交给原执行实例处理的控制信号。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ExecutionControlSignalDTO {
    private String sessionId;

    private String executionId;

    private String rootEventId;

    private String segmentId;

    private boolean stopRequested;

    private String toolCallId;

    public ExecutionTargetDTO target() {
        return new ExecutionTargetDTO(sessionId, executionId, rootEventId, segmentId);
    }
}
