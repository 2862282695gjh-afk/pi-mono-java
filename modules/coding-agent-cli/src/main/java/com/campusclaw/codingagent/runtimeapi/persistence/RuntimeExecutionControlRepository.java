/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedControlEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ConfirmationAcceptanceDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ConfirmingEventsDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.InterruptRequestDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationResult;

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

    ConfirmationAcceptanceDTO acceptConfirmation(
            String sessionId,
            String toolCallId,
            String confirmationEventId,
            String segmentId,
            ToolConfirmationResult result,
            String denyMessage,
            ConfirmationAppender appender,
            OffsetDateTime acceptedAt);

    Optional<ToolConfirmationDecisionDTO> claimConfirmation(
            ExecutionTargetDTO confirmingTarget, String toolCallId, OffsetDateTime claimedAt);

    boolean acknowledgeConfirmation(ToolConfirmationDecisionDTO decision, OffsetDateTime completedAt);

    TransitionStatus markTerminal(
            ExecutionTargetDTO target,
            TerminalAppender appender,
            RuntimeExecutionTerminalReason terminalReason,
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

    /**
     * 持有 Session 与执行行锁期间提交 confirming 完整事件的回调。
     */
    @FunctionalInterface
    interface ConfirmingAppender {
        ConfirmingEventsDTO append();
    }

    /**
     * 持有 Session 与执行行锁期间提交工具确认回执的回调。
     */
    @FunctionalInterface
    interface ConfirmationAppender {
        CommittedControlEventDTO append();
    }

    /**
     * 持有 Session 与执行行锁期间提交唯一权威终态事件的回调。
     */
    @FunctionalInterface
    interface TerminalAppender {
        CommittedControlEventDTO append();
    }
}
