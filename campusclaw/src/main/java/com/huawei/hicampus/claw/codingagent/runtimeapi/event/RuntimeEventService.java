/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeExecutionContextDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.UserEventAcceptance;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionModelReconciler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 接受 user.message，并建立本轮执行所需的 Session、模型和 SSE 上下文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeEventService.class);

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
            RuntimeErrorCode errorCode = RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.events.accept")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("sessionId", sessionId)
                    .setCause(error)
                    .log("CampusClaw failure: operation={}, errorCode={}", "runtime.events.accept", errorCode.name());
            throw new RuntimeApiException(errorCode);
        }
    }

    private RuntimeEventStream prepareAndSubmit(
            String sessionId, ValidatedUserEvent request, Locale locale, MateCredentials credentials) {
        return engineRegistry.withOperationLock(
                sessionId, () -> prepareAndSubmitLocked(sessionId, request, null, locale, credentials));
    }

    /**
     * 在实际 Agent 工厂准备的同一快照上生成消息，随后复用普通消息接受与执行。
     *
     * @param sessionId Session 标识
     * @param messageFactory 无外部副作用的消息准备回调，在实际快照上同步执行一次
     * @param fileIds 已由调用服务校验并归一的附件列表
     * @param locale 本次语言
     * @param credentials 本次透传凭据
     * @return 普通消息 SSE 流
     */
    public RuntimeEventStream submitPreparedMessage(
            String sessionId,
            BiFunction<String, PreparedAgentRuntime, String> messageFactory,
            List<String> fileIds,
            Locale locale,
            MateCredentials credentials) {
        var request = new ValidatedUserEvent(null, List.copyOf(fileIds));
        return engineRegistry.withOperationLock(
                sessionId, () -> prepareAndSubmitLocked(sessionId, request, messageFactory, locale, credentials));
    }

    private RuntimeEventStream prepareAndSubmitLocked(
            String sessionId,
            ValidatedUserEvent request,
            BiFunction<String, PreparedAgentRuntime, String> messageFactory,
            Locale locale,
            MateCredentials credentials) {
        RuntimeExecutionContextDTO context = null;
        try {
            RuntimeSessionDTO session = requireIdleSession(sessionId);
            var reconciled = modelReconciler.reconcile(session);
            context = messageFactory == null
                    ? executionContextFactory.create(
                            reconciled.session(),
                            reconciled.agentSnapshot(),
                            reconciled.model(),
                            request.message(),
                            request.fileIds(),
                            credentials)
                    : executionContextFactory.createPreparedMessage(
                            reconciled.session(),
                            reconciled.agentSnapshot(),
                            reconciled.model(),
                            runtime -> messageFactory.apply(session.getAgentId(), runtime),
                            request.fileIds(),
                            credentials);
            emitConfigurationEntries(context.eventStream(), reconciled.configurationEntries(), locale);
            acceptUserEntry(sessionId, request, context, locale);
            executionCoordinator.start(context.holder(), context.execution(), context.userMessage(), locale);
            return context.eventStream();
        } catch (RuntimeException error) {
            releaseUnacceptedExecution(context);
            throw error;
        }
    }

    private void emitConfigurationEntries(RuntimeEventStream stream, List<RuntimeEntryDTO> entries, Locale locale) {
        for (RuntimeEntryDTO entry : entries) {
            stream.emit(new RuntimeSseEventVO(
                    Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry, locale)));
        }
    }

    private void acceptUserEntry(
            String sessionId, ValidatedUserEvent request, RuntimeExecutionContextDTO context, Locale locale) {
        RuntimeEntryDTO entry =
                codec.userEntry(sessionId, idGenerator.nextId(), context.message(), request.fileIds(), now());
        UserEventAcceptance acceptance = repository.acceptUserEvent(sessionId, entry, now());
        requireAccepted(acceptance);
        context.execution().beginRun(entry.getId());
        context.eventStream()
                .emit(new RuntimeSseEventVO(
                        Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry, locale)));
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
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
        }
    }

    private void releaseUnacceptedExecution(RuntimeExecutionContextDTO context) {
        if (context != null) {
            engineRegistry.complete(context.holder(), context.execution());
            context.eventStream().complete();
            context.execution().complete(null);
        }
    }

    private static RuntimeApiException invalidEventRequest() {
        return new RuntimeApiException(RuntimeErrorCode.INVALID_EVENT_REQUEST);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record ValidatedUserEvent(String message, List<String> fileIds) {}
}
