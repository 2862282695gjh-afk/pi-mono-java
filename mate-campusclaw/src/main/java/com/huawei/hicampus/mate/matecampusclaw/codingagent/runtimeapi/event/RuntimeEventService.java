/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeFailures;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.springframework.stereotype.Service;

/**
 * 接受 user.message，并建立本轮执行所需的 Session、模型和 SSE 上下文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeEventService {
    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator idGenerator;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeExecutionContextFactory executionContextFactory;

    private final RuntimeExecutionCoordinator executionCoordinator;

    private final RuntimeSessionModelReconciler modelReconciler;

    private final Clock clock;

    public RuntimeEventService(
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator idGenerator,
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeExecutionContextFactory executionContextFactory,
            RuntimeExecutionCoordinator executionCoordinator,
            RuntimeSessionModelReconciler modelReconciler,
            Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.idGenerator = idGenerator;
        this.engineRegistry = engineRegistry;
        this.executionContextFactory = executionContextFactory;
        this.executionCoordinator = executionCoordinator;
        this.modelReconciler = modelReconciler;
        this.clock = clock;
    }

    public RuntimeEventStream submit(
            String sessionId, UserEventRequestVO request, Locale locale, MateCredentials credentials) {
        try {
            return prepareAndSubmit(sessionId, validate(request), locale, credentials);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw RuntimeFailures.raise(
                    "runtime.events.accept", RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED, error, "sessionId", sessionId);
        }
    }

    private RuntimeEventStream prepareAndSubmit(
            String sessionId, ValidatedUserEvent request, Locale locale, MateCredentials credentials) {
        engineRegistry.lockOperation(sessionId);
        RuntimeExecutionContext context = null;
        try {
            RuntimeSessionDTO session = requireIdleSession(sessionId);
            var reconciled = modelReconciler.reconcile(session);
            context = executionContextFactory.create(
                    reconciled.session(),
                    reconciled.agentSnapshot(),
                    reconciled.model(),
                    request.message(),
                    request.fileIds(),
                    credentials);
            emitConfigurationEntries(context.execution().eventStream(), reconciled.configurationEntries());
            acceptUserEntry(sessionId, request, context);
            executionCoordinator.start(context.holder(), context.execution(), context.userMessage(), locale);
            return context.execution().eventStream();
        } catch (RuntimeException error) {
            releaseUnacceptedExecution(context);
            throw error;
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private void emitConfigurationEntries(RuntimeEventStream stream, List<RuntimeEntryDTO> entries) {
        for (RuntimeEntryDTO entry : entries) {
            stream.emit(
                    new RuntimeSseEventVO(Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
        }
    }

    private void acceptUserEntry(String sessionId, ValidatedUserEvent request, RuntimeExecutionContext context) {
        RuntimeEntryDTO entry =
                codec.userEntry(sessionId, idGenerator.nextId(), request.message(), request.fileIds(), now());
        UserEventAcceptance acceptance = repository.acceptUserEvent(sessionId, entry, now());
        requireAccepted(acceptance);
        context.execution().beginRun(entry.getId());
        context.execution()
                .eventStream()
                .emit(new RuntimeSseEventVO(
                        Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
    }

    private RuntimeSessionDTO requireIdleSession(String sessionId) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> RuntimeFailures.raise(
                        "runtime.session.find", RuntimeErrorCode.SESSION_NOT_FOUND, "sessionId", sessionId));
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            throw RuntimeFailures.raise("runtime.events.accept", RuntimeErrorCode.SESSION_BUSY, "sessionId", sessionId);
        }
        return session;
    }

    private static ValidatedUserEvent validate(UserEventRequestVO request) {
        if (request == null) {
            throw invalidEventRequest();
        }
        List<String> fileIds = request.getFileIds() == null ? List.of() : List.copyOf(request.getFileIds());
        String message = request.getMessage();
        if ((message == null && fileIds.isEmpty())
                || (message != null && message.isBlank())
                || new HashSet<>(fileIds).size() != fileIds.size()) {
            throw invalidEventRequest();
        }
        return new ValidatedUserEvent(message, fileIds);
    }

    private static void requireAccepted(UserEventAcceptance acceptance) {
        switch (acceptance.status()) {
            case ACCEPTED -> {}
            case NOT_FOUND -> throw RuntimeFailures.raise("runtime.events.persist", RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw RuntimeFailures.raise("runtime.events.persist", RuntimeErrorCode.SESSION_BUSY);
        }
    }

    private void releaseUnacceptedExecution(RuntimeExecutionContext context) {
        if (context != null) {
            engineRegistry.complete(context.holder(), context.execution());
            context.execution().eventStream().complete();
            context.execution().complete(null);
        }
    }

    private static RuntimeApiException invalidEventRequest() {
        return RuntimeFailures.raise("runtime.events.validate", RuntimeErrorCode.INVALID_EVENT_REQUEST);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record ValidatedUserEvent(String message, List<String> fileIds) {}
}
