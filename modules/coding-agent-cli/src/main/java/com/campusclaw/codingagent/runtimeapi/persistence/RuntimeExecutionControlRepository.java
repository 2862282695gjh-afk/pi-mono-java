/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;

/**
 * 固定消息执行、续跑段和公共事件关联的持久化端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeExecutionControlRepository {
    void register(ExecutionTargetDTO target, long triggerEventSeq, OffsetDateTime acceptedAt);

    Optional<ExecutionStateDTO> find(ExecutionTargetDTO target);

    void linkCommittedEvent(ExecutionTargetDTO target, String eventId, long eventSeq);

    TransitionStatus markConfirming(
            ExecutionTargetDTO target,
            String toolCallId,
            String terminalEventId,
            long terminalEventSeq,
            OffsetDateTime terminalAt);

    TransitionStatus markTerminal(
            ExecutionTargetDTO target,
            String terminalEventId,
            long terminalEventSeq,
            String terminalReason,
            OffsetDateTime terminalAt);

    /**
     * 带固定执行和段校验的状态迁移结果。
     */
    enum TransitionStatus {
        APPLIED,
        NOT_FOUND,
        STALE_TARGET,
        STATE_CONFLICT
    }
}
