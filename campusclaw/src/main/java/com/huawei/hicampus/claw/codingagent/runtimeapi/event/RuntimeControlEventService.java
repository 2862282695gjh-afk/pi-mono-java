/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedControlDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedControlStreamDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedQuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.PlanModeEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.PlanConfirmationResult;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 原子受理控制事件，并把完整回执和固定权威结果绑定到请求范围事件流。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeControlEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeControlEventService.class);

    private final RuntimeEventStreamFactory streams;

    private final RuntimeResultWaitRegistry waits;

    private final RuntimeResultPollingService results;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeEventEncoder encoder;

    private final Clock clock;

    private final RuntimeUserQuestionService questions;

    public RuntimeControlEventService(
            RuntimeEventStreamFactory streams,
            RuntimeResultWaitRegistry waits,
            RuntimeResultPollingService results,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeExecutionPersistenceService persistence,
            RuntimeCommittedEventFactory events,
            RuntimeEventEncoder encoder,
            Clock clock) {
        this(streams, waits, results, codec, ids, persistence, events, encoder, clock, null);
    }

    @Autowired
    public RuntimeControlEventService(
            RuntimeEventStreamFactory streams,
            RuntimeResultWaitRegistry waits,
            RuntimeResultPollingService results,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeExecutionPersistenceService persistence,
            RuntimeCommittedEventFactory events,
            RuntimeEventEncoder encoder,
            Clock clock,
            RuntimeUserQuestionService questions) {
        this.streams = streams;
        this.waits = waits;
        this.results = results;
        this.codec = codec;
        this.ids = ids;
        this.persistence = persistence;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
        this.questions = questions;
    }

    public AcceptedControlStreamDTO acceptInterrupt(String sessionId, String targetEventId) {
        OffsetDateTime acceptedAt = now();
        RuntimeEntryDTO receipt = codec.userInterruptEntry(sessionId, ids.nextId(), targetEventId, acceptedAt);
        CommittedEventDTO event = events.userInterrupt(receipt, targetEventId);
        return accept(
                RuntimeResultWaitMode.EXECUTION_TERMINAL,
                event,
                () -> persistence.acceptInterrupt(sessionId, targetEventId, receipt, event, acceptedAt));
    }

    public AcceptedControlStreamDTO acceptToolConfirmation(
            String sessionId, String toolCallId, ToolConfirmationResult result, String denyMessage) {
        OffsetDateTime acceptedAt = now();
        RuntimeEntryDTO receipt = codec.userToolConfirmationEntry(
                sessionId, ids.nextId(), toolCallId, result.value(), denyMessage, acceptedAt);
        CommittedEventDTO event = events.userToolConfirmation(receipt, toolCallId, result.value(), denyMessage);
        return accept(
                RuntimeResultWaitMode.SEGMENT_EVENTS,
                event,
                () -> persistence.acceptToolConfirmation(
                        sessionId, toolCallId, result, denyMessage, receipt, event, acceptedAt));
    }

    public AcceptedControlStreamDTO acceptPlanConfirmation(
            String sessionId, String targetEventId, PlanConfirmationResult result, String feedback) {
        OffsetDateTime acceptedAt = now();
        RuntimeEntryDTO receipt = codec.userPlanConfirmationEntry(
                sessionId, ids.nextId(), targetEventId, result.value(), feedback, acceptedAt);
        CommittedEventDTO receiptEvent = events.userPlanConfirmation(receipt, targetEventId, result.value(), feedback);
        return accept(
                RuntimeResultWaitMode.SEGMENT_EVENTS,
                receiptEvent,
                () -> persistence.acceptPlanConfirmation(
                        sessionId,
                        targetEventId,
                        result,
                        feedback,
                        receipt,
                        receiptEvent,
                        sourceEventId -> planModeEvent(sessionId, sourceEventId, receiptEvent, result, acceptedAt),
                        acceptedAt));
    }

    public AcceptedControlStreamDTO acceptQuestionAnswer(
            String sessionId, String targetEventId, List<QuestionAnswerDTO> submittedAnswers) {
        if (questions == null) {
            throw new RuntimeApiException(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
        OffsetDateTime acceptedAt = now();
        String answerEventId = ids.nextId();
        RuntimeEventStream stream = streams.create();
        RuntimeResultWaitRegistry.Reservation reservation = reserve(RuntimeResultWaitMode.SEGMENT_EVENTS, stream);
        stream.onClose(reservation::close);
        var prepared = new AtomicReference<RuntimeSseEventVO>();
        AcceptedQuestionAnswerDTO accepted = runQuestionTransaction(
                sessionId, targetEventId, submittedAnswers, answerEventId, acceptedAt, stream, prepared);
        AcceptedControlDTO control = new AcceptedControlDTO(accepted.receipt(), accepted.target());
        return finishAccepted(
                stream, reservation, prepared.get(), control, accepted.event().getEventSeq());
    }

    private AcceptedQuestionAnswerDTO runQuestionTransaction(
            String sessionId,
            String targetEventId,
            List<QuestionAnswerDTO> submittedAnswers,
            String answerEventId,
            OffsetDateTime acceptedAt,
            RuntimeEventStream stream,
            AtomicReference<RuntimeSseEventVO> prepared) {
        try {
            return persistence.acceptQuestionAnswer(
                    sessionId,
                    targetEventId,
                    answerEventId,
                    () -> questionAnswerEvent(
                            sessionId, targetEventId, submittedAnswers, answerEventId, acceptedAt, stream, prepared),
                    acceptedAt);
        } catch (RuntimeApiException error) {
            stream.detach();
            throw error;
        } catch (RuntimeException error) {
            stream.detach();
            throw acceptanceFailure(CommittedEventType.USER_QUESTION_ANSWER.value(), error);
        }
    }

    private QuestionAnswerEventDTO questionAnswerEvent(
            String sessionId,
            String targetEventId,
            List<QuestionAnswerDTO> submittedAnswers,
            String answerEventId,
            OffsetDateTime acceptedAt,
            RuntimeEventStream stream,
            AtomicReference<RuntimeSseEventVO> prepared) {
        QuestionAnswerResultDTO normalized = questions.normalizeCurrent(sessionId, targetEventId, submittedAnswers);
        RuntimeEntryDTO receipt =
                codec.userQuestionAnswerEntry(sessionId, answerEventId, targetEventId, normalized, acceptedAt);
        CommittedEventDTO event = events.userQuestionAnswer(receipt, targetEventId, normalized);
        RuntimeSseEventVO frame = encoder.committed(event);
        if (!stream.canAcceptRequired(frame)) {
            throw new RuntimeApiException(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
        prepared.set(frame);
        return new QuestionAnswerEventDTO(receipt, event);
    }

    private PlanModeEventDTO planModeEvent(
            String sessionId,
            String sourceEventId,
            CommittedEventDTO receiptEvent,
            PlanConfirmationResult result,
            OffsetDateTime acceptedAt) {
        if (!result.isApproved()) {
            return null;
        }
        RuntimeEntryDTO entry = codec.modeChangedEntry(
                sessionId,
                ids.nextId(),
                ClawConstants.Agent.MODE_PLAN,
                ClawConstants.Agent.MODE_BUILD,
                "planApproved",
                sourceEventId,
                receiptEvent.getEventId(),
                acceptedAt);
        return new PlanModeEventDTO(entry, events.sessionModeChanged(entry));
    }

    private AcceptedControlStreamDTO accept(
            RuntimeResultWaitMode mode, CommittedEventDTO event, Supplier<AcceptedControlDTO> transaction) {
        RuntimeEventStream stream = streams.create();
        RuntimeResultWaitRegistry.Reservation reservation = reserve(mode, stream);
        stream.onClose(reservation::close);
        RuntimeSseEventVO receipt = encodeBeforeAcceptance(stream, event);
        AcceptedControlDTO accepted = runTransaction(transaction, stream, event.getType());
        return finishAccepted(stream, reservation, receipt, accepted, event.getEventSeq());
    }

    private RuntimeResultWaitRegistry.Reservation reserve(RuntimeResultWaitMode mode, RuntimeEventStream stream) {
        return waits.reserve(mode, (events, terminal) -> deliver(stream, events, terminal), stream::complete)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE));
    }

    private RuntimeSseEventVO encodeBeforeAcceptance(RuntimeEventStream stream, CommittedEventDTO event) {
        RuntimeSseEventVO receipt;
        try {
            receipt = encoder.committed(event);
        } catch (RuntimeException error) {
            stream.detach();
            throw acceptanceFailure(event.getType(), error);
        }
        if (!stream.canAcceptRequired(receipt)) {
            stream.detach();
            throw new RuntimeApiException(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
        return receipt;
    }

    private AcceptedControlDTO runTransaction(
            Supplier<AcceptedControlDTO> transaction, RuntimeEventStream stream, String eventType) {
        try {
            return transaction.get();
        } catch (RuntimeApiException error) {
            stream.detach();
            throw error;
        } catch (RuntimeException error) {
            stream.detach();
            throw acceptanceFailure(eventType, error);
        }
    }

    private AcceptedControlStreamDTO finishAccepted(
            RuntimeEventStream stream,
            RuntimeResultWaitRegistry.Reservation reservation,
            RuntimeSseEventVO receipt,
            AcceptedControlDTO accepted,
            long eventSeq) {
        try {
            if (!stream.emit(Objects.requireNonNull(receipt, "prepared receipt"))) {
                stream.detach();
                return new AcceptedControlStreamDTO(stream, accepted);
            }
        } catch (RuntimeException error) {
            logAcceptedStreamFailure(accepted, error);
            stream.detach();
            return new AcceptedControlStreamDTO(stream, accepted);
        }
        if (!reservation.bind(accepted.target(), eventSeq)) {
            stream.complete();
        } else {
            results.notifyCommitted(accepted.target());
        }
        return new AcceptedControlStreamDTO(stream, accepted);
    }

    private boolean deliver(RuntimeEventStream stream, List<CommittedEventDTO> events, boolean terminal) {
        try {
            for (CommittedEventDTO event : events) {
                if (!stream.emit(encoder.committed(event))) {
                    return false;
                }
            }
            if (terminal) {
                stream.complete();
            }
            return true;
        } catch (RuntimeException error) {
            stream.detach();
            LOGGER.warn("Failed to enqueue committed Runtime control result", error);
            return false;
        }
    }

    private RuntimeApiException acceptanceFailure(String eventType, RuntimeException error) {
        LOGGER.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.events.accept.control")
                .addKeyValue("eventType", eventType)
                .addKeyValue("errorCode", RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED.name())
                .setCause(error)
                .log("CampusClaw control event acceptance failed");
        return new RuntimeApiException(RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED);
    }

    private static void logAcceptedStreamFailure(AcceptedControlDTO accepted, RuntimeException error) {
        LOGGER.atWarn()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.events.accepted.control.output")
                .addKeyValue("sessionId", accepted.target().sessionId())
                .addKeyValue("executionId", accepted.target().executionId())
                .setCause(error)
                .log("Accepted Runtime control receipt could not be queued");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
