/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SegmentEventBatchDTO;

/**
 * 按已接受的固定执行身份补读权威结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeExecutionResultRepository {
    Optional<SegmentEventBatchDTO> readSegmentEvents(ExecutionTargetDTO target, long afterSeq, int limit);

    Optional<CommittedEventDTO> findExecutionTerminal(ExecutionTargetDTO target);
}
