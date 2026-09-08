/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeExecutionContextDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.UserMessageAcceptanceDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageContentRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageEventRequestVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 原子受理 v2 user.message，并在完整回执入队后启动固定目标执行。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeV2MessageEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeV2MessageEventService.class);

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeSessionEngineRegistry engines;

    private final RuntimeExecutionContextFactory contexts;

    private final RuntimeV2ExecutionCoordinator coordinator;

    private final RuntimeSessionModelReconciler reconciler;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeV2EventEncoder encoder;

    private final Clock clock;

    public RuntimeV2MessageEventService(
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeSessionEngineRegistry engines,
            RuntimeExecutionContextFactory contexts,
            RuntimeV2ExecutionCoordinator coordinator,
            RuntimeSessionModelReconciler reconciler,
            RuntimeExecutionPersistenceService persistence,
            RuntimeCommittedEventFactory events,
            RuntimeV2EventEncoder encoder,
            Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.ids = ids;
        this.engines = engines;
        this.contexts = contexts;
        this.coordinator = coordinator;
        this.reconciler = reconciler;
        this.persistence = persistence;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
    }

    public RuntimeEventStream submit(
            String sessionId, UserMessageEventRequestVO request, Locale locale, MateCredentials credentials) {
        RuntimeUserMessageDTO message = toMessage(request);
        return submitPrepared(sessionId, message, null, locale, credentials);
    }

    public RuntimeEventStream submitPreparedMessage(
            String sessionId,
            String publicText,
            BiFunction<String, PreparedAgentRuntime, String> messageFactory,
            List<String> fileIds,
            Locale locale,
            MateCredentials credentials) {
        Objects.requireNonNull(messageFactory, "messageFactory");
        var message = new RuntimeUserMessageDTO(publicText, List.copyOf(fileIds));
        return submitPrepared(sessionId, message, messageFactory, locale, credentials);
    }

    private RuntimeEventStream submitPrepared(
            String sessionId,
            RuntimeUserMessageDTO message,
            BiFunction<String, PreparedAgentRuntime, String> messageFactory,
            Locale locale,
            MateCredentials credentials) {
        try {
            return engines.withOperationLock(
                    sessionId, () -> submitLocked(sessionId, message, messageFactory, locale, credentials));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            logAcceptanceFailure(sessionId, error);
            throw new RuntimeApiException(RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED);
        }
    }

    private RuntimeEventStream submitLocked(
            String sessionId,
            RuntimeUserMessageDTO message,
            BiFunction<String, PreparedAgentRuntime, String> messageFactory,
            Locale locale,
            MateCredentials credentials) {
        RuntimeExecutionContextDTO context = prepare(sessionId, message, messageFactory, credentials);
        AcceptedMessageDTO accepted;
        try {
            accepted = accept(sessionId, message, context);
        } catch (RuntimeException error) {
            releaseUnaccepted(context);
            throw error;
        }
        startAccepted(context, accepted, locale);
        return context.eventStream();
    }

    private RuntimeExecutionContextDTO prepare(
            String sessionId,
            RuntimeUserMessageDTO message,
            BiFunction<String, PreparedAgentRuntime, String> messageFactory,
            MateCredentials credentials) {
        RuntimeSessionDTO session = requireIdleSession(sessionId);
        var reconciled = reconciler.reconcile(session);
        if (messageFactory == null) {
            return contexts.create(
                    reconciled.session(),
                    reconciled.agentSnapshot(),
                    reconciled.model(),
                    message.publicText(),
                    message.fileIds(),
                    credentials);
        }
        return contexts.createPreparedMessage(
                reconciled.session(),
                reconciled.agentSnapshot(),
                reconciled.model(),
                runtime -> messageFactory.apply(session.getAgentId(), runtime),
                message.fileIds(),
                credentials);
    }

    private AcceptedMessageDTO accept(
            String sessionId, RuntimeUserMessageDTO message, RuntimeExecutionContextDTO context) {
        OffsetDateTime acceptedAt = now();
        RuntimeEntryDTO receipt =
                codec.userEntry(sessionId, ids.nextId(), context.message(), message.fileIds(), acceptedAt);
        CommittedEventDTO event = events.userMessage(receipt, message.publicText(), message.fileIds());
        RuntimeSseEventVO frame = encoder.committed(event);
        if (!context.eventStream().canAcceptRequired(frame)) {
            throw new RuntimeApiException(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE);
        }
        UserMessageAcceptanceDTO acceptance = persistence.acceptMessage(sessionId, receipt, event, acceptedAt);
        return new AcceptedMessageDTO(acceptance, event);
    }

    private void startAccepted(RuntimeExecutionContextDTO context, AcceptedMessageDTO accepted, Locale locale) {
        try {
            context.execution().bindTarget(accepted.acceptance().target());
            context.execution().beginRun(accepted.acceptance().target().executionId());
            RuntimeSseEventVO receipt = encoder.committed(accepted.event());
            if (!context.eventStream().emit(receipt)) {
                throw new IllegalStateException("accepted user message receipt could not be queued");
            }
            coordinator.start(context.holder(), context.execution(), context.userMessage(), locale);
        } catch (RuntimeException error) {
            coordinator.handleAcceptedStartFailure(context.holder(), context.execution(), error, locale);
        }
    }

    private RuntimeSessionDTO requireIdleSession(String sessionId) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
        }
        return session;
    }

    private void releaseUnaccepted(RuntimeExecutionContextDTO context) {
        engines.complete(context.holder(), context.execution());
        context.eventStream().complete();
        context.execution().complete(null);
    }

    private static RuntimeUserMessageDTO toMessage(UserMessageEventRequestVO request) {
        Objects.requireNonNull(request, "request");
        String text = null;
        List<String> files = new ArrayList<>();
        for (UserMessageContentRequestVO block : request.getContent()) {
            if (block instanceof UserMessageContentRequestVO.TextRequestVO value) {
                text = value.getText();
            } else if (block instanceof UserMessageContentRequestVO.FileRequestVO value) {
                files.add(value.getFileId());
            }
        }
        return new RuntimeUserMessageDTO(text, List.copyOf(files));
    }

    private static void logAcceptanceFailure(String sessionId, RuntimeException error) {
        LOGGER.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.events.v2.accept")
                .addKeyValue("errorCode", RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED.name())
                .addKeyValue("sessionId", sessionId)
                .setCause(error)
                .log("CampusClaw v2 event acceptance failed");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record AcceptedMessageDTO(UserMessageAcceptanceDTO acceptance, CommittedEventDTO event) {}

    private record RuntimeUserMessageDTO(String publicText, List<String> fileIds) {}
}
