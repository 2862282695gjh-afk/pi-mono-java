/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedControlDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedControlStreamDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 原子受理 v2 控制事件，并把完整回执和固定权威结果绑定到请求范围事件流。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeV2ControlEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeV2ControlEventService.class);

    private final RuntimeEventStreamFactory streams;

    private final RuntimeResultWaitRegistry waits;

    private final RuntimeResultPollingService results;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeV2EventEncoder encoder;

    private final Clock clock;

    public RuntimeV2ControlEventService(
            RuntimeEventStreamFactory streams,
            RuntimeResultWaitRegistry waits,
            RuntimeResultPollingService results,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeExecutionPersistenceService persistence,
            RuntimeCommittedEventFactory events,
            RuntimeV2EventEncoder encoder,
            Clock clock) {
        this.streams = streams;
        this.waits = waits;
        this.results = results;
        this.codec = codec;
        this.ids = ids;
        this.persistence = persistence;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
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

    private AcceptedControlStreamDTO accept(
            RuntimeResultWaitMode mode, CommittedEventDTO event, Supplier<AcceptedControlDTO> transaction) {
        RuntimeEventStream stream = streams.create();
        RuntimeResultWaitRegistry.Reservation reservation = reserve(mode, stream);
        stream.onClose(reservation::close);
        RuntimeSseEventVO receipt = encodeBeforeAcceptance(stream, event);
        AcceptedControlDTO accepted = runTransaction(transaction, stream, event.getType());
        if (!emitAccepted(stream, receipt, accepted, event)) {
            return new AcceptedControlStreamDTO(stream, accepted);
        }
        if (!reservation.bind(accepted.target(), event.getEventSeq())) {
            stream.complete();
            return new AcceptedControlStreamDTO(stream, accepted);
        }
        results.notifyCommitted(accepted.target());
        return new AcceptedControlStreamDTO(stream, accepted);
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

    private boolean emitAccepted(
            RuntimeEventStream stream,
            RuntimeSseEventVO preparedReceipt,
            AcceptedControlDTO accepted,
            CommittedEventDTO event) {
        try {
            RuntimeSseEventVO receipt = event.getEventSeq() == 0L ? preparedReceipt : encoder.committed(event);
            if (stream.emit(receipt)) {
                return true;
            }
        } catch (RuntimeException error) {
            logAcceptedStreamFailure(accepted, error);
        }
        stream.detach();
        return false;
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
                .addKeyValue("operation", "runtime.events.v2.accept.control")
                .addKeyValue("eventType", eventType)
                .addKeyValue("errorCode", RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED.name())
                .setCause(error)
                .log("CampusClaw v2 control event acceptance failed");
        return new RuntimeApiException(RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED);
    }

    private static void logAcceptedStreamFailure(AcceptedControlDTO accepted, RuntimeException error) {
        LOGGER.atWarn()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.events.v2.accepted.control.output")
                .addKeyValue("sessionId", accepted.target().sessionId())
                .addKeyValue("executionId", accepted.target().executionId())
                .setCause(error)
                .log("Accepted Runtime control receipt could not be queued");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
