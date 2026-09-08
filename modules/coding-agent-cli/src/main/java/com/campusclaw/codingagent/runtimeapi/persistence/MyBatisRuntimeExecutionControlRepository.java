/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.ConfirmingEventsDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionSegmentDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.InterruptRequestDTO;
import com.campusclaw.codingagent.runtimeapi.dto.InterruptRequestDTO.Status;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeExecutionControlMapper;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionSegmentState;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionState;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 MyBatis 和 openGauss 保存固定执行及其 HTTP 结果段。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Repository
public class MyBatisRuntimeExecutionControlRepository implements RuntimeExecutionControlRepository {
    private final RuntimeExecutionControlMapper mapper;

    private final RuntimeSessionMapper sessionMapper;

    public MyBatisRuntimeExecutionControlRepository(
            RuntimeExecutionControlMapper mapper, RuntimeSessionMapper sessionMapper) {
        this.mapper = mapper;
        this.sessionMapper = sessionMapper;
    }

    @Override
    @Transactional
    public void register(ExecutionTargetDTO target, long triggerEventSeq, OffsetDateTime acceptedAt) {
        requireTarget(target);
        var session = sessionMapper.lockSessionForUpdate(target.sessionId());
        if (session == null || !RuntimeSessionState.RUNNING.matches(session.getState())) {
            throw new IllegalStateException("running session is required for execution registration");
        }
        OffsetDateTime storedAt = storedAt(acceptedAt);
        requireOne(mapper.insertExecution(newExecution(target, storedAt)), "execution was not inserted");
        requireOne(
                mapper.insertSegment(newInitialSegment(target, triggerEventSeq, storedAt)),
                "execution segment was not inserted");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionStateDTO> find(ExecutionTargetDTO target) {
        requireTarget(target);
        return Optional.ofNullable(mapper.findExecution(
                target.sessionId(), target.executionId(), target.rootEventId(), target.segmentId()));
    }

    @Override
    @Transactional
    public void linkCommittedEvent(ExecutionTargetDTO target, String eventId, long eventSeq) {
        requireTarget(target);
        if (eventId == null || eventId.isBlank() || eventSeq < 1) {
            throw new IllegalArgumentException("committed event identity is invalid");
        }
        requireOne(
                mapper.insertSegmentEvent(
                        target.sessionId(), target.executionId(), target.segmentId(), eventId, eventSeq),
                "execution segment event was not linked");
    }

    @Override
    @Transactional
    public InterruptRequestDTO requestInterrupt(
            String sessionId, String targetEventId, String stopEventId, OffsetDateTime requestedAt) {
        requireControlEvent(sessionId, targetEventId, stopEventId);
        var session = sessionMapper.lockSessionForUpdate(sessionId);
        if (session == null) {
            return new InterruptRequestDTO(Status.SESSION_NOT_FOUND, null);
        }
        if (!RuntimeSessionState.RUNNING.matches(session.getState())) {
            return new InterruptRequestDTO(Status.SESSION_NOT_RUNNING, null);
        }
        ExecutionStateDTO execution = mapper.lockCurrentExecution(sessionId);
        Status rejected = rejectInterrupt(targetEventId, execution);
        if (rejected != null) {
            return new InterruptRequestDTO(rejected, null);
        }
        var target = targetOf(execution);
        requireOne(
                mapper.markStopping(
                        sessionId, target.executionId(), target.segmentId(), stopEventId, storedAt(requestedAt)),
                "execution did not enter stopping state");
        return new InterruptRequestDTO(Status.ACCEPTED, target);
    }

    @Override
    @Transactional
    public TransitionStatus markConfirming(
            ExecutionTargetDTO target, String toolCallId, ConfirmingAppender appender, OffsetDateTime terminalAt) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("tool call id is required");
        }
        Objects.requireNonNull(appender, "appender");
        TransitionStatus rejected = rejectTransition(target, RuntimeExecutionState.RUNNING);
        if (rejected != null) {
            return rejected;
        }
        ConfirmingEventsDTO events = requireConfirmingEvents(appender.append());
        linkCommittedEvent(target, events.toolCallEventId(), events.toolCallEventSeq());
        linkCommittedEvent(target, events.idleEventId(), events.idleEventSeq());
        OffsetDateTime storedAt = storedAt(terminalAt);
        closeSegment(
                target,
                events.idleEventId(),
                events.idleEventSeq(),
                RuntimeExecutionTerminalReason.CONFIRMING,
                storedAt);
        requireOne(
                mapper.markConfirming(
                        target.sessionId(), target.executionId(), target.segmentId(), toolCallId, storedAt),
                "execution did not enter confirming state");
        return TransitionStatus.APPLIED;
    }

    @Override
    @Transactional
    public TransitionStatus markTerminal(
            ExecutionTargetDTO target,
            String terminalEventId,
            long terminalEventSeq,
            RuntimeExecutionTerminalReason terminalReason,
            OffsetDateTime terminalAt) {
        if (terminalReason == null || !terminalReason.executionTerminal()) {
            throw new IllegalArgumentException("terminal reason is invalid");
        }
        ExecutionStateDTO execution = lock(target);
        TransitionStatus rejected = rejectTarget(target, execution);
        if (rejected != null) {
            return rejected;
        }
        OffsetDateTime storedAt = storedAt(terminalAt);
        closeOpenSegment(target, terminalEventId, terminalEventSeq, terminalReason, storedAt);
        requireOne(
                mapper.markTerminal(
                        target.sessionId(),
                        target.executionId(),
                        target.segmentId(),
                        terminalEventId,
                        terminalReason.value(),
                        storedAt),
                "execution did not enter terminal state");
        return TransitionStatus.APPLIED;
    }

    private TransitionStatus rejectTransition(ExecutionTargetDTO target, RuntimeExecutionState expectedState) {
        ExecutionStateDTO execution = lock(target);
        TransitionStatus rejected = rejectTarget(target, execution);
        if (rejected != null) {
            return rejected;
        }
        return expectedState == execution.getState() ? null : TransitionStatus.STATE_CONFLICT;
    }

    private ExecutionStateDTO lock(ExecutionTargetDTO target) {
        requireTarget(target);
        if (sessionMapper.lockSessionForUpdate(target.sessionId()) == null) {
            return null;
        }
        return mapper.lockExecution(target.sessionId(), target.executionId());
    }

    private static TransitionStatus rejectTarget(ExecutionTargetDTO target, ExecutionStateDTO execution) {
        if (execution == null) {
            return TransitionStatus.NOT_FOUND;
        }
        if (!target.rootEventId().equals(execution.getRootEventId())
                || !target.segmentId().equals(execution.getCurrentSegmentId())) {
            return TransitionStatus.STALE_TARGET;
        }
        return execution.getState() == RuntimeExecutionState.TERMINAL ? TransitionStatus.STATE_CONFLICT : null;
    }

    private static Status rejectInterrupt(String targetEventId, ExecutionStateDTO execution) {
        if (execution == null) {
            return Status.SESSION_NOT_RUNNING;
        }
        if (!targetEventId.equals(execution.getRootEventId())) {
            return Status.TARGET_MISMATCH;
        }
        if (execution.getState() == RuntimeExecutionState.STOPPING || execution.getStopEventId() != null) {
            return Status.ALREADY_REQUESTED;
        }
        return null;
    }

    private static ExecutionTargetDTO targetOf(ExecutionStateDTO execution) {
        return new ExecutionTargetDTO(
                execution.getSessionId(),
                execution.getExecutionId(),
                execution.getRootEventId(),
                execution.getCurrentSegmentId());
    }

    private void closeSegment(
            ExecutionTargetDTO target,
            String terminalEventId,
            long terminalEventSeq,
            RuntimeExecutionTerminalReason terminalReason,
            OffsetDateTime terminalAt) {
        requireTerminalEvent(terminalEventId, terminalEventSeq);
        requireOne(
                mapper.closeSegment(
                        target.sessionId(),
                        target.executionId(),
                        target.segmentId(),
                        terminalEventId,
                        terminalEventSeq,
                        terminalReason.value(),
                        terminalAt),
                "execution segment was not closed");
    }

    private void closeOpenSegment(
            ExecutionTargetDTO target,
            String terminalEventId,
            long terminalEventSeq,
            RuntimeExecutionTerminalReason terminalReason,
            OffsetDateTime terminalAt) {
        requireTerminalEvent(terminalEventId, terminalEventSeq);
        mapper.closeSegment(
                target.sessionId(),
                target.executionId(),
                target.segmentId(),
                terminalEventId,
                terminalEventSeq,
                terminalReason.value(),
                terminalAt);
    }

    private static ExecutionStateDTO newExecution(ExecutionTargetDTO target, OffsetDateTime acceptedAt) {
        var execution = new ExecutionStateDTO();
        execution.setSessionId(target.sessionId());
        execution.setExecutionId(target.executionId());
        execution.setRootEventId(target.rootEventId());
        execution.setState(RuntimeExecutionState.RUNNING);
        execution.setCurrentSegmentId(target.segmentId());
        execution.setCreatedAt(acceptedAt);
        execution.setUpdatedAt(acceptedAt);
        return execution;
    }

    private static ExecutionSegmentDTO newInitialSegment(
            ExecutionTargetDTO target, long triggerEventSeq, OffsetDateTime acceptedAt) {
        if (triggerEventSeq < 1) {
            throw new IllegalArgumentException("trigger event sequence must be positive");
        }
        var segment = new ExecutionSegmentDTO();
        segment.setSessionId(target.sessionId());
        segment.setExecutionId(target.executionId());
        segment.setSegmentId(target.segmentId());
        segment.setSegmentOrdinal(1);
        segment.setTriggerEventId(target.rootEventId());
        segment.setTriggerEventSeq(triggerEventSeq);
        segment.setState(RuntimeExecutionSegmentState.OPEN);
        segment.setCreatedAt(acceptedAt);
        segment.setUpdatedAt(acceptedAt);
        return segment;
    }

    private static void requireTarget(ExecutionTargetDTO target) {
        Objects.requireNonNull(target, "target");
        if (isBlank(target.sessionId())
                || isBlank(target.executionId())
                || isBlank(target.rootEventId())
                || isBlank(target.segmentId())) {
            throw new IllegalArgumentException("execution target is incomplete");
        }
    }

    private static void requireControlEvent(String sessionId, String targetEventId, String eventId) {
        if (isBlank(sessionId) || isBlank(targetEventId) || isBlank(eventId)) {
            throw new IllegalArgumentException("interrupt identity is incomplete");
        }
    }

    private static ConfirmingEventsDTO requireConfirmingEvents(ConfirmingEventsDTO events) {
        Objects.requireNonNull(events, "confirming events");
        requireTerminalEvent(events.toolCallEventId(), events.toolCallEventSeq());
        requireTerminalEvent(events.idleEventId(), events.idleEventSeq());
        if (events.toolCallEventId().equals(events.idleEventId())) {
            throw new IllegalArgumentException("confirming event identities must be distinct");
        }
        return events;
    }

    private static void requireTerminalEvent(String eventId, long eventSeq) {
        if (eventId == null || eventId.isBlank() || eventSeq < 1) {
            throw new IllegalArgumentException("terminal event identity is invalid");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static OffsetDateTime storedAt(OffsetDateTime value) {
        return Objects.requireNonNull(value, "timestamp").truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireOne(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }
}
