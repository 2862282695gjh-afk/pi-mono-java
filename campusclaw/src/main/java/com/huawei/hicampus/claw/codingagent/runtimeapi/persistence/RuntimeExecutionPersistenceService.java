/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedControlDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedControlEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ConfirmationAcceptanceDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ConfirmingEventsDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.InterruptRequestDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.UserMessageAcceptanceDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.CommittedEventType;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventType;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationResult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个数据库事务中提交公共事件、执行控制身份和 Session 状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeExecutionPersistenceService {
    private final RuntimeSessionRepository sessions;

    private final RuntimeExecutionControlRepository controls;

    private final RuntimeEntryIdGenerator ids;

    public RuntimeExecutionPersistenceService(
            RuntimeSessionRepository sessions,
            RuntimeExecutionControlRepository controls,
            RuntimeEntryIdGenerator ids) {
        this.sessions = sessions;
        this.controls = controls;
        this.ids = ids;
    }

    @Transactional
    public UserMessageAcceptanceDTO acceptMessage(
            String sessionId, RuntimeEntryDTO receipt, CommittedEventDTO event, OffsetDateTime acceptedAt) {
        requireMessageEvent(sessionId, receipt, event);
        var target = new ExecutionTargetDTO(sessionId, ids.nextId(), event.getEventId(), ids.nextId());
        UserEventAcceptance acceptance = sessions.acceptUserEvent(sessionId, receipt, event, acceptedAt);
        requireAccepted(acceptance);
        controls.register(target, event.getEventSeq(), acceptedAt);
        controls.linkCommittedEvent(target, event.getEventId(), event.getEventSeq());
        return new UserMessageAcceptanceDTO(receipt, target);
    }

    @Transactional
    public AcceptedControlDTO acceptInterrupt(
            String sessionId,
            String targetEventId,
            RuntimeEntryDTO receipt,
            CommittedEventDTO event,
            OffsetDateTime acceptedAt) {
        requireControlEvent(sessionId, receipt, event, CommittedEventType.USER_INTERRUPT);
        var request = controls.requestInterrupt(sessionId, targetEventId, event.getEventId(), acceptedAt);
        ExecutionTargetDTO target = requireAcceptedInterrupt(request);
        sessions.appendEntry(receipt, List.of(event));
        return new AcceptedControlDTO(receipt, target);
    }

    @Transactional
    public void markToolConfirming(
            ExecutionTargetDTO target,
            String toolCallId,
            RuntimeEntryDTO toolCall,
            CommittedEventDTO toolCallEvent,
            RuntimeEntryDTO idle,
            CommittedEventDTO idleEvent,
            OffsetDateTime confirmingAt) {
        requireEvent(
                target.sessionId(),
                toolCall,
                toolCallEvent,
                RuntimeEventType.TOOL_EXECUTION_STARTED,
                CommittedEventType.AGENT_TOOL_CALL);
        requireEvent(
                target.sessionId(),
                idle,
                idleEvent,
                RuntimeEventType.SESSION_STATUS_IDLE,
                CommittedEventType.SESSION_STATUS_IDLE);
        var status = controls.markConfirming(
                target,
                toolCallId,
                () -> appendConfirmingEvents(toolCall, toolCallEvent, idle, idleEvent),
                confirmingAt);
        if (status != RuntimeExecutionControlRepository.TransitionStatus.APPLIED) {
            throw new IllegalStateException("confirming target is no longer active: " + status);
        }
    }

    @Transactional
    public AcceptedControlDTO acceptToolConfirmation(
            String sessionId,
            String toolCallId,
            ToolConfirmationResult result,
            String denyMessage,
            RuntimeEntryDTO receipt,
            CommittedEventDTO event,
            OffsetDateTime acceptedAt) {
        requireControlEvent(sessionId, receipt, event, CommittedEventType.USER_TOOL_CONFIRMATION);
        var acceptance = controls.acceptConfirmation(
                sessionId,
                toolCallId,
                event.getEventId(),
                ids.nextId(),
                result,
                denyMessage,
                () -> appendControlEvent(receipt, event),
                acceptedAt);
        return new AcceptedControlDTO(receipt, requireAcceptedConfirmation(acceptance));
    }

    @Transactional
    public RuntimeEntryDTO commitTerminal(
            ExecutionTargetDTO target,
            RuntimeEntryDTO entry,
            CommittedEventDTO event,
            RuntimeExecutionTerminalReason terminalReason,
            OffsetDateTime terminalAt) {
        requireEvent(
                target.sessionId(),
                entry,
                event,
                RuntimeEventType.SESSION_STATUS_IDLE,
                CommittedEventType.SESSION_STATUS_IDLE);
        var status = controls.markTerminal(target, () -> appendControlEvent(entry, event), terminalReason, terminalAt);
        if (status != RuntimeExecutionControlRepository.TransitionStatus.APPLIED) {
            throw new IllegalStateException("terminal target is no longer active: " + status);
        }
        sessions.finishExecution(target.sessionId(), terminalAt);
        return entry;
    }

    private static void requireMessageEvent(String sessionId, RuntimeEntryDTO receipt, CommittedEventDTO event) {
        requireEvent(sessionId, receipt, event, RuntimeEventType.USER_MESSAGE, CommittedEventType.USER_MESSAGE);
    }

    private static void requireEvent(
            String sessionId,
            RuntimeEntryDTO entry,
            CommittedEventDTO event,
            RuntimeEventType expectedEntryType,
            CommittedEventType expectedEventType) {
        requireEventValues(sessionId, entry, event, expectedEntryType.value(), expectedEventType.value());
    }

    private static void requireControlEvent(
            String sessionId, RuntimeEntryDTO entry, CommittedEventDTO event, CommittedEventType expectedType) {
        requireEventValues(sessionId, entry, event, expectedType.value(), expectedType.value());
    }

    private static void requireEventValues(
            String sessionId,
            RuntimeEntryDTO entry,
            CommittedEventDTO event,
            String expectedEntryType,
            String expectedEventType) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(event, "event");
        requireSessionAndAnchor(sessionId, entry, event);
        if (!expectedEntryType.equals(entry.getType()) || !expectedEventType.equals(event.getType())) {
            throw new IllegalArgumentException("entry and committed event do not match");
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("committed event id is required");
        }
    }

    private static void requireSessionAndAnchor(String sessionId, RuntimeEntryDTO entry, CommittedEventDTO event) {
        if (!sessionId.equals(entry.getSessionId()) || !sessionId.equals(event.getSessionId())) {
            throw new IllegalArgumentException("entry and committed event do not match");
        }
        if (!entry.getId().equals(event.getAnchorEntryId())) {
            throw new IllegalArgumentException("committed event anchor does not match entry");
        }
    }

    private static void requireAccepted(UserEventAcceptance acceptance) {
        switch (acceptance.status()) {
            case ACCEPTED -> {}
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
        }
    }

    private static ExecutionTargetDTO requireAcceptedInterrupt(InterruptRequestDTO request) {
        return switch (request.status()) {
            case ACCEPTED -> request.target();
            case SESSION_NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case SESSION_NOT_RUNNING -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_RUNNING);
            case TARGET_MISMATCH -> throw new RuntimeApiException(RuntimeErrorCode.INTERRUPT_TARGET_MISMATCH);
            case ALREADY_REQUESTED -> throw new RuntimeApiException(RuntimeErrorCode.INTERRUPT_ALREADY_REQUESTED);
        };
    }

    private static ExecutionTargetDTO requireAcceptedConfirmation(ConfirmationAcceptanceDTO acceptance) {
        return switch (acceptance.status()) {
            case ACCEPTED -> acceptance.target();
            case SESSION_NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case NOT_PENDING -> throw new RuntimeApiException(RuntimeErrorCode.TOOL_CONFIRMATION_NOT_PENDING);
            case EXECUTION_STOPPING -> throw new RuntimeApiException(RuntimeErrorCode.EXECUTION_STOPPING);
        };
    }

    private ConfirmingEventsDTO appendConfirmingEvents(
            RuntimeEntryDTO toolCall,
            CommittedEventDTO toolCallEvent,
            RuntimeEntryDTO idle,
            CommittedEventDTO idleEvent) {
        sessions.appendEntry(toolCall, List.of(toolCallEvent));
        sessions.appendEntry(idle, List.of(idleEvent));
        return new ConfirmingEventsDTO(
                toolCallEvent.getEventId(),
                toolCallEvent.getEventSeq(),
                idleEvent.getEventId(),
                idleEvent.getEventSeq());
    }

    private CommittedControlEventDTO appendControlEvent(RuntimeEntryDTO entry, CommittedEventDTO event) {
        sessions.appendEntry(entry, List.of(event));
        return new CommittedControlEventDTO(event.getEventId(), event.getEventSeq());
    }
}
