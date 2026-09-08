/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.util.List;

import lombok.Data;

/**
 * 固定执行结果段的一批已提交完整事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class SegmentEventBatchDTO {
    private List<CommittedEventDTO> events;

    private boolean terminal;
}
