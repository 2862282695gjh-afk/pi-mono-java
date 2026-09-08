/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedControlEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedTerminalDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ConfirmationAcceptanceDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ConfirmingEventsDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionControlSignalDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionSegmentDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionStateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.InterruptRequestDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeExecutionControlMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionSegmentState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationState;

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
            return new InterruptRequestDTO(InterruptRequestDTO.Status.SESSION_NOT_FOUND, null);
        }
        if (!RuntimeSessionState.RUNNING.matches(session.getState())) {
            return new InterruptRequestDTO(InterruptRequestDTO.Status.SESSION_NOT_RUNNING, null);
        }
        ExecutionStateDTO execution = mapper.lockCurrentExecution(sessionId);
        InterruptRequestDTO.Status rejected = rejectInterrupt(targetEventId, execution);
        if (rejected != null) {
            return new InterruptRequestDTO(rejected, null);
        }
        var target = targetOf(execution);
        requireOne(
                mapper.markStopping(
                        sessionId, target.executionId(), target.segmentId(), stopEventId, storedAt(requestedAt)),
                "execution did not enter stopping state");
        return new InterruptRequestDTO(InterruptRequestDTO.Status.ACCEPTED, target);
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
    public ConfirmationAcceptanceDTO acceptConfirmation(
            String sessionId,
            String toolCallId,
            String confirmationEventId,
            String segmentId,
            ToolConfirmationResult result,
            String denyMessage,
            ConfirmationAppender appender,
            OffsetDateTime acceptedAt) {
        requireConfirmation(sessionId, toolCallId, confirmationEventId, segmentId, result, denyMessage, appender);
        var session = sessionMapper.lockSessionForUpdate(sessionId);
        if (session == null) {
            return new ConfirmationAcceptanceDTO(ConfirmationAcceptanceDTO.Status.SESSION_NOT_FOUND, null);
        }
        if (!RuntimeSessionState.RUNNING.matches(session.getState())) {
            return new ConfirmationAcceptanceDTO(ConfirmationAcceptanceDTO.Status.NOT_PENDING, null);
        }
        ExecutionStateDTO execution = mapper.lockCurrentExecution(sessionId);
        ConfirmationAcceptanceDTO.Status rejected = rejectConfirmation(toolCallId, execution);
        if (rejected != null) {
            return new ConfirmationAcceptanceDTO(rejected, null);
        }
        CommittedControlEventDTO event = requireControlEvent(appender.append(), confirmationEventId);
        OffsetDateTime storedAt = storedAt(acceptedAt);
        ExecutionTargetDTO target = continuationTarget(execution, segmentId);
        persistConfirmation(execution, target, event, toolCallId, result, denyMessage, storedAt);
        return new ConfirmationAcceptanceDTO(ConfirmationAcceptanceDTO.Status.ACCEPTED, target);
    }

    @Override
    @Transactional
    public Optional<ToolConfirmationDecisionDTO> claimConfirmation(
            ExecutionTargetDTO confirmingTarget, String toolCallId, OffsetDateTime claimedAt) {
        requireTarget(confirmingTarget);
        if (isBlank(toolCallId)) {
            throw new IllegalArgumentException("tool call id is required");
        }
        ExecutionStateDTO execution = lockExecutionForClaim(confirmingTarget);
        if (cannotClaim(execution, confirmingTarget)) {
            return Optional.empty();
        }
        int affected = mapper.claimConfirmation(
                confirmingTarget.sessionId(),
                confirmingTarget.executionId(),
                confirmingTarget.rootEventId(),
                confirmingTarget.segmentId(),
                toolCallId,
                storedAt(claimedAt));
        if (affected == 0) {
            return Optional.empty();
        }
        return Optional.of(requireClaimedConfirmation(confirmingTarget, toolCallId));
    }

    @Override
    @Transactional
    public boolean acknowledgeConfirmation(ToolConfirmationDecisionDTO decision, OffsetDateTime completedAt) {
        Objects.requireNonNull(decision, "decision");
        if (isBlank(decision.getSessionId())
                || isBlank(decision.getExecutionId())
                || isBlank(decision.getConfirmationEventId())) {
            throw new IllegalArgumentException("confirmation decision identity is incomplete");
        }
        return mapper.acknowledgeConfirmation(
                        decision.getSessionId(),
                        decision.getExecutionId(),
                        decision.getConfirmationEventId(),
                        storedAt(completedAt))
                == 1;
    }

    @Override
    @Transactional(readOnly = true, timeoutString = "${campusclaw.runtime.execution.control-query-timeout-seconds:2}")
    public List<ExecutionControlSignalDTO> findPendingControls(List<ExecutionTargetDTO> targets) {
        Objects.requireNonNull(targets, "targets");
        List<ExecutionTargetDTO> distinctTargets = targets.stream().distinct().toList();
        if (distinctTargets.isEmpty()) {
            return List.of();
        }
        distinctTargets.forEach(MyBatisRuntimeExecutionControlRepository::requireTarget);
        return mapper.findPendingControls(distinctTargets);
    }

    @Override
    @Transactional
    public TransitionStatus appendToSegment(ExecutionTargetDTO target, SegmentAppender appender) {
        Objects.requireNonNull(appender, "appender");
        ExecutionStateDTO execution = lock(target);
        TransitionStatus rejected = rejectSegmentAppend(target, execution);
        if (rejected != null) {
            return rejected;
        }
        ExecutionSegmentDTO segment = mapper.lockSegment(target.sessionId(), target.executionId(), target.segmentId());
        if (segment == null || segment.getState() != RuntimeExecutionSegmentState.OPEN) {
            return TransitionStatus.STATE_CONFLICT;
        }
        List<CommittedControlEventDTO> events = List.copyOf(Objects.requireNonNull(appender.append(), "events"));
        events.forEach(MyBatisRuntimeExecutionControlRepository::requireCommittedEvent);
        events.forEach(event -> linkCommittedEvent(target, event.eventId(), event.eventSeq()));
        return TransitionStatus.APPLIED;
    }

    @Override
    @Transactional
    public TransitionStatus markTerminal(
            ExecutionTargetDTO target,
            String terminalEventId,
            TerminalAppender appender,
            RuntimeExecutionTerminalReason terminalReason,
            OffsetDateTime terminalAt) {
        if (terminalReason == null || !terminalReason.executionTerminal()) {
            throw new IllegalArgumentException("terminal reason is invalid");
        }
        requireEventId(terminalEventId);
        Objects.requireNonNull(appender, "appender");
        ExecutionStateDTO execution = lock(target);
        TransitionStatus rejected = rejectTerminal(target, execution, terminalEventId, terminalReason);
        if (rejected != null) {
            return rejected;
        }
        CommittedControlEventDTO event = requireCommittedEvent(appender.append());
        if (!terminalEventId.equals(event.eventId())) {
            throw new IllegalArgumentException("terminal event id changed during append");
        }
        linkCommittedEvent(target, event.eventId(), event.eventSeq());
        OffsetDateTime storedAt = storedAt(terminalAt);
        closeOpenSegment(target, event.eventId(), event.eventSeq(), terminalReason, storedAt);
        requireOne(
                mapper.markTerminal(
                        target.sessionId(),
                        target.executionId(),
                        target.segmentId(),
                        event.eventId(),
                        terminalReason.value(),
                        storedAt),
                "execution did not enter terminal state");
        return TransitionStatus.APPLIED;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommittedTerminalDTO> findCommittedTerminal(
            ExecutionTargetDTO target, String terminalEventId, RuntimeExecutionTerminalReason terminalReason) {
        requireTarget(target);
        requireEventId(terminalEventId);
        if (terminalReason == null || !terminalReason.executionTerminal()) {
            throw new IllegalArgumentException("terminal reason is invalid");
        }
        return Optional.ofNullable(mapper.findCommittedTerminal(target, terminalEventId, terminalReason.value()));
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

    private ExecutionStateDTO lockExecutionForClaim(ExecutionTargetDTO target) {
        if (sessionMapper.lockSessionForUpdate(target.sessionId()) == null) {
            return null;
        }
        return mapper.lockExecution(target.sessionId(), target.executionId());
    }

    private static boolean cannotClaim(ExecutionStateDTO execution, ExecutionTargetDTO target) {
        return execution == null
                || !target.rootEventId().equals(execution.getRootEventId())
                || execution.getState() != RuntimeExecutionState.RUNNING
                || execution.getStopEventId() != null;
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

    private static TransitionStatus rejectTerminal(
            ExecutionTargetDTO target,
            ExecutionStateDTO execution,
            String terminalEventId,
            RuntimeExecutionTerminalReason terminalReason) {
        TransitionStatus rejected = rejectTarget(target, execution);
        if (rejected != TransitionStatus.STATE_CONFLICT) {
            return rejected;
        }
        boolean sameTerminal = terminalEventId.equals(execution.getTerminalEventId())
                && terminalReason == execution.getTerminalReason();
        return sameTerminal ? TransitionStatus.ALREADY_APPLIED : TransitionStatus.STATE_CONFLICT;
    }

    private static TransitionStatus rejectSegmentAppend(ExecutionTargetDTO target, ExecutionStateDTO execution) {
        TransitionStatus rejected = rejectTarget(target, execution);
        if (rejected != null) {
            return rejected;
        }
        boolean appendable = execution.getState() == RuntimeExecutionState.RUNNING
                || execution.getState() == RuntimeExecutionState.STOPPING;
        return appendable ? null : TransitionStatus.STATE_CONFLICT;
    }

    private static InterruptRequestDTO.Status rejectInterrupt(String targetEventId, ExecutionStateDTO execution) {
        if (execution == null) {
            return InterruptRequestDTO.Status.SESSION_NOT_RUNNING;
        }
        if (!targetEventId.equals(execution.getRootEventId())) {
            return InterruptRequestDTO.Status.TARGET_MISMATCH;
        }
        if (execution.getState() == RuntimeExecutionState.STOPPING || execution.getStopEventId() != null) {
            return InterruptRequestDTO.Status.ALREADY_REQUESTED;
        }
        return null;
    }

    private static ConfirmationAcceptanceDTO.Status rejectConfirmation(String toolCallId, ExecutionStateDTO execution) {
        if (execution != null
                && (execution.getState() == RuntimeExecutionState.STOPPING || execution.getStopEventId() != null)) {
            return ConfirmationAcceptanceDTO.Status.EXECUTION_STOPPING;
        }
        if (execution == null
                || execution.getState() != RuntimeExecutionState.CONFIRMING
                || !toolCallId.equals(execution.getPendingToolCallId())) {
            return ConfirmationAcceptanceDTO.Status.NOT_PENDING;
        }
        return null;
    }

    private void persistConfirmation(
            ExecutionStateDTO execution,
            ExecutionTargetDTO target,
            CommittedControlEventDTO event,
            String toolCallId,
            ToolConfirmationResult result,
            String denyMessage,
            OffsetDateTime acceptedAt) {
        var segment = newContinuationSegment(target, event, acceptedAt);
        requireOne(mapper.insertSegment(segment), "confirmation segment was not inserted");
        requireOne(
                mapper.insertConfirmation(
                        newConfirmation(execution, target, event, toolCallId, result, denyMessage, acceptedAt)),
                "confirmation decision was not inserted");
        requireOne(
                mapper.resumeAfterConfirmation(
                        target.sessionId(),
                        target.executionId(),
                        execution.getCurrentSegmentId(),
                        target.segmentId(),
                        toolCallId,
                        acceptedAt),
                "execution did not accept confirmation");
        linkCommittedEvent(target, event.eventId(), event.eventSeq());
    }

    private static ExecutionTargetDTO targetOf(ExecutionStateDTO execution) {
        return new ExecutionTargetDTO(
                execution.getSessionId(),
                execution.getExecutionId(),
                execution.getRootEventId(),
                execution.getCurrentSegmentId());
    }

    private static ExecutionTargetDTO continuationTarget(ExecutionStateDTO execution, String segmentId) {
        return new ExecutionTargetDTO(
                execution.getSessionId(), execution.getExecutionId(), execution.getRootEventId(), segmentId);
    }

    private ExecutionSegmentDTO newContinuationSegment(
            ExecutionTargetDTO target, CommittedControlEventDTO event, OffsetDateTime acceptedAt) {
        var segment = new ExecutionSegmentDTO();
        segment.setSessionId(target.sessionId());
        segment.setExecutionId(target.executionId());
        segment.setSegmentId(target.segmentId());
        segment.setSegmentOrdinal(mapper.nextSegmentOrdinal(target.sessionId(), target.executionId()));
        segment.setTriggerEventId(event.eventId());
        segment.setTriggerEventSeq(event.eventSeq());
        segment.setState(RuntimeExecutionSegmentState.OPEN);
        segment.setCreatedAt(acceptedAt);
        segment.setUpdatedAt(acceptedAt);
        return segment;
    }

    private static ToolConfirmationDecisionDTO newConfirmation(
            ExecutionStateDTO execution,
            ExecutionTargetDTO target,
            CommittedControlEventDTO event,
            String toolCallId,
            ToolConfirmationResult result,
            String denyMessage,
            OffsetDateTime acceptedAt) {
        var decision = new ToolConfirmationDecisionDTO();
        decision.setSessionId(target.sessionId());
        decision.setExecutionId(target.executionId());
        decision.setConfirmationEventId(event.eventId());
        decision.setPreviousSegmentId(execution.getCurrentSegmentId());
        decision.setSegmentId(target.segmentId());
        decision.setToolCallId(toolCallId);
        decision.setResult(result);
        decision.setDenyMessage(denyMessage);
        decision.setState(ToolConfirmationState.PENDING);
        decision.setCreatedAt(acceptedAt);
        return decision;
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

    private static void requireConfirmation(
            String sessionId,
            String toolCallId,
            String eventId,
            String segmentId,
            ToolConfirmationResult result,
            String denyMessage,
            ConfirmationAppender appender) {
        if (isBlank(sessionId) || isBlank(toolCallId) || isBlank(eventId) || isBlank(segmentId) || result == null) {
            throw new IllegalArgumentException("confirmation identity is incomplete");
        }
        if (result == ToolConfirmationResult.ALLOW && denyMessage != null) {
            throw new IllegalArgumentException("allow confirmation cannot carry deny message");
        }
        Objects.requireNonNull(appender, "appender");
    }

    private static CommittedControlEventDTO requireControlEvent(
            CommittedControlEventDTO event, String expectedEventId) {
        Objects.requireNonNull(event, "committed control event");
        requireTerminalEvent(event.eventId(), event.eventSeq());
        if (!expectedEventId.equals(event.eventId())) {
            throw new IllegalArgumentException("confirmation event identity changed while appending");
        }
        return event;
    }

    private ToolConfirmationDecisionDTO requireClaimedConfirmation(ExecutionTargetDTO target, String toolCallId) {
        ToolConfirmationDecisionDTO decision = mapper.findClaimedConfirmation(
                target.sessionId(), target.executionId(), target.segmentId(), toolCallId);
        if (decision == null) {
            throw new IllegalStateException("claimed confirmation decision is missing");
        }
        return decision;
    }

    private static CommittedControlEventDTO requireCommittedEvent(CommittedControlEventDTO event) {
        Objects.requireNonNull(event, "committed control event");
        requireTerminalEvent(event.eventId(), event.eventSeq());
        return event;
    }

    private static void requireTerminalEvent(String eventId, long eventSeq) {
        if (eventId == null || eventId.isBlank() || eventSeq < 1) {
            throw new IllegalArgumentException("terminal event identity is invalid");
        }
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("terminal event id is invalid");
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
