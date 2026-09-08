/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;

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

    InterruptRequest requestInterrupt(
            String sessionId, String targetEventId, String stopEventId, OffsetDateTime requestedAt);

    TransitionStatus markConfirming(
            ExecutionTargetDTO target, String toolCallId, ConfirmingAppender appender, OffsetDateTime terminalAt);

    TransitionStatus markTerminal(
            ExecutionTargetDTO target,
            String terminalEventId,
            long terminalEventSeq,
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
     * user.interrupt 在锁内复核并登记停止请求的结果。
     */
    enum InterruptStatus {
        ACCEPTED,
        SESSION_NOT_FOUND,
        SESSION_NOT_RUNNING,
        TARGET_MISMATCH,
        ALREADY_REQUESTED
    }

    /**
     * 停止请求结果及接受时固定的执行目标。
     *
     * @param status 锁内复核结果
     * @param target 接受时固定的执行目标；拒绝时为空
     */
    record InterruptRequest(InterruptStatus status, ExecutionTargetDTO target) {}

    /**
     * 持有 Session 与执行行锁期间提交 confirming 完整事件的回调。
     */
    @FunctionalInterface
    interface ConfirmingAppender {
        ConfirmingEvents append();
    }

    /**
     * 已原子提交的工具调用和 confirming idle 事件身份。
     *
     * @param toolCallEventId 工具调用完整事件标识
     * @param toolCallEventSeq 工具调用完整事件顺序号
     * @param idleEventId confirming idle 完整事件标识
     * @param idleEventSeq confirming idle 完整事件顺序号
     */
    record ConfirmingEvents(String toolCallEventId, long toolCallEventSeq, String idleEventId, long idleEventSeq) {}
}
