/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedControlEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedTerminalDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ConfirmingEventsDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.InterruptRequestDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;

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

    InterruptRequestDTO requestInterrupt(
            String sessionId, String targetEventId, String stopEventId, OffsetDateTime requestedAt);

    TransitionStatus markConfirming(
            ExecutionTargetDTO target, String toolCallId, ConfirmingAppender appender, OffsetDateTime terminalAt);

    TransitionStatus appendToSegment(ExecutionTargetDTO target, SegmentAppender appender);

    TransitionStatus markTerminal(
            ExecutionTargetDTO target,
            String terminalEventId,
            TerminalAppender appender,
            RuntimeExecutionTerminalReason terminalReason,
            OffsetDateTime terminalAt);

    Optional<CommittedTerminalDTO> findCommittedTerminal(
            ExecutionTargetDTO target, String terminalEventId, RuntimeExecutionTerminalReason terminalReason);

    /**
     * 带固定执行和段校验的状态迁移结果。
     */
    enum TransitionStatus {
        APPLIED,
        ALREADY_APPLIED,
        NOT_FOUND,
        STALE_TARGET,
        STATE_CONFLICT
    }

    /**
     * 持有 Session 与执行行锁期间提交 confirming 完整事件的回调。
     */
    @FunctionalInterface
    interface ConfirmingAppender {
        ConfirmingEventsDTO append();
    }

    /**
     * 持有 Session、执行和结果段行锁期间提交普通完整事件的回调。
     */
    @FunctionalInterface
    interface SegmentAppender {
        List<CommittedControlEventDTO> append();
    }

    /**
     * 持有 Session 与执行行锁期间提交唯一权威终态事件的回调。
     */
    @FunctionalInterface
    interface TerminalAppender {
        CommittedControlEventDTO append();
    }
}
