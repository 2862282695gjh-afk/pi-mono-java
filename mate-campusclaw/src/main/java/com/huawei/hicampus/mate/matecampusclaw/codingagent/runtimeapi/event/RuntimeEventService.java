/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.EventPageResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;
import reactor.core.publisher.Flux;

/**
 * user.message 执行、公共事件投影与当前分支历史分页的业务 Service。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeEventService {
    private static final int DEFAULT_LIMIT = 100;

    private static final int MAX_LIMIT = 200;

    private static final int RESTORE_LIMIT = 1_000_000;

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator idGenerator;

    private final RuntimeFileResolver fileResolver;

    private final AgentRuntimeSnapshotProvider snapshotProvider;

    private final RuntimeModelManager modelManager;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeEventCursorCodec cursorCodec;

    private final Validator validator;

    private final Clock clock;

    public RuntimeEventService(
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator idGenerator,
            RuntimeFileResolver fileResolver,
            AgentRuntimeSnapshotProvider snapshotProvider,
            RuntimeModelManager modelManager,
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeEventCursorCodec cursorCodec,
            Validator validator,
            Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.idGenerator = idGenerator;
        this.fileResolver = fileResolver;
        this.snapshotProvider = snapshotProvider;
        this.modelManager = modelManager;
        this.engineRegistry = engineRegistry;
        this.cursorCodec = cursorCodec;
        this.validator = validator;
        this.clock = clock;
    }

    public Flux<RuntimeSseEventVO> submit(
            String sessionId, CallerAuthContext caller, UserEventRequestVO request, boolean chinese) {
        try {
            return prepareAndSubmit(sessionId, caller, request, chinese);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw mapAcceptanceError(error);
        }
    }

    public EventPageResponseVO list(String sessionId, CallerAuthContext caller, String limitValue, String page) {
        try {
            int limit = parseLimit(limitValue);
            requireOwnedSession(sessionId, caller, false);
            long afterSeq = page == null ? 0 : cursorCodec.decode(page, sessionId);
            List<RuntimeEntryDTO> entries = repository.listCurrentBranch(sessionId, afterSeq, limit + 1);
            boolean more = entries.size() > limit;
            List<RuntimeEntryDTO> pageEntries = more ? entries.subList(0, limit) : entries;
            List<java.util.Map<String, Object>> events =
                    pageEntries.stream().map(codec::toHistoryEvent).toList();
            String nextPage =
                    more ? cursorCodec.encode(sessionId, pageEntries.getLast().getEntrySeq()) : null;
            return new EventPageResponseVO(events, nextPage);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.EVENT_LIST_FAILED, error);
        }
    }

    private Flux<RuntimeSseEventVO> prepareAndSubmit(
            String sessionId, CallerAuthContext caller, UserEventRequestVO request, boolean chinese) {
        ValidatedUserEvent validated = validate(request);
        RuntimeSessionDTO session = requireOwnedSession(sessionId, caller, true);
        List<ContentBlock> fileContent = fileResolver.resolve(sessionId, validated.fileIds());
        UserMessage userMessage = toUserMessage(validated.message(), fileContent);
        RuntimeActiveExecution execution = new RuntimeActiveExecution(new RuntimeEventStream());
        RuntimeSessionHolder holder = null;
        engineRegistry.lockOperation(sessionId);
        try {
            RuntimeSessionDTO current = requireOwnedSession(sessionId, caller, true);
            holder = requireEngine(current);
            if (!holder.begin(execution)) {
                throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_BUSY);
            }
            return acceptAndStart(sessionId, caller, validated, userMessage, chinese, holder, execution);
        } catch (RuntimeException error) {
            if (holder != null) {
                holder.complete(execution);
            }
            throw error;
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private Flux<RuntimeSseEventVO> acceptAndStart(
            String sessionId,
            CallerAuthContext caller,
            ValidatedUserEvent validated,
            UserMessage message,
            boolean chinese,
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution) {
        OffsetDateTime now = now();
        RuntimeEntryDTO userEntry =
                codec.userEntry(sessionId, idGenerator.nextId(), validated.message(), validated.fileIds(), now);
        UserEventAcceptance acceptance = repository.acceptUserEvent(sessionId, caller.callerId(), userEntry, now);
        requireAccepted(acceptance);
        execution
                .eventStream()
                .emit(new RuntimeSseEventVO(
                        Long.toString(userEntry.getEntrySeq()), userEntry.getType(), codec.toSseData(userEntry)));
        startAgent(holder, execution, message, chinese);
        return execution.eventStream().flux();
    }

    private void startAgent(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, UserMessage message, boolean chinese) {
        RuntimeEventProjector projector = new RuntimeEventProjector(
                holder.sessionId(),
                repository,
                codec,
                idGenerator,
                execution.eventStream(),
                clock,
                holder.agent()::abort);
        Runnable unsubscribe = () -> {};
        try {
            unsubscribe = holder.agent().subscribe(projector::onEvent);
            CompletableFuture<Void> future = holder.agent().prompt(message);
            Runnable finalUnsubscribe = unsubscribe;
            future.whenComplete(
                    (unused, error) -> finishAgent(holder, execution, projector, finalUnsubscribe, error, chinese));
        } catch (RuntimeException error) {
            finishAgent(holder, execution, projector, unsubscribe, error, chinese);
        }
    }

    private void finishAgent(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Runnable unsubscribe,
            Throwable executionError,
            boolean chinese) {
        engineRegistry.lockOperation(holder.sessionId());
        try {
            if (continueQueuedExecution(holder, execution, projector, unsubscribe, executionError, chinese)) {
                return;
            }
            completeExecution(holder, execution, projector, unsubscribe, executionError, chinese);
        } finally {
            engineRegistry.unlockOperation(holder.sessionId());
        }
    }

    private boolean continueQueuedExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Runnable unsubscribe,
            Throwable executionError,
            boolean chinese) {
        if (executionError != null
                || projector.failure() != null
                || projector.terminalReason() == StopReason.ERROR
                || projector.terminalReason() == StopReason.ABORTED
                || !execution.acceptingControls()
                || !holder.agent().hasQueuedControlMessages()) {
            return false;
        }
        CompletableFuture<Void> future;
        try {
            future = holder.agent().continueQueuedExecution();
        } catch (RuntimeException error) {
            completeExecution(holder, execution, projector, unsubscribe, error, chinese);
            return true;
        }
        future.whenComplete((unused, error) -> finishAgent(holder, execution, projector, unsubscribe, error, chinese));
        return true;
    }

    private void completeExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Runnable unsubscribe,
            Throwable executionError,
            boolean chinese) {
        execution.closeControls();
        Throwable failure = finishPersistence(holder, executionError, projector);
        StopReason reason = execution.abortRequested() ? StopReason.ABORTED : projector.terminalReason();
        failure = emitTerminalEvents(execution.eventStream(), reason, failure, chinese);
        cleanupExecution(holder, execution, unsubscribe, failure);
    }

    private Throwable emitTerminalEvents(
            RuntimeEventStream stream, StopReason reason, Throwable failure, boolean chinese) {
        try {
            if (failure != null || reason == StopReason.ERROR) {
                emitStreamError(stream, chinese);
            } else {
                emitSuccessfulEnd(stream, reason);
            }
            return failure;
        } catch (RuntimeException streamError) {
            return failure != null ? failure : streamError;
        }
    }

    private static void cleanupExecution(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, Runnable unsubscribe, Throwable failure) {
        Throwable terminalFailure = failure;
        try {
            unsubscribe.run();
        } catch (RuntimeException unsubscribeError) {
            terminalFailure = terminalFailure != null ? terminalFailure : unsubscribeError;
        } finally {
            holder.complete(execution);
            try {
                execution.eventStream().complete();
            } finally {
                execution.complete(terminalFailure);
            }
        }
    }

    private Throwable finishPersistence(
            RuntimeSessionHolder holder, Throwable executionError, RuntimeEventProjector projector) {
        Throwable failure = executionError != null ? executionError : projector.failure();
        try {
            repository.finishExecution(holder.sessionId(), now());
            return failure;
        } catch (RuntimeException persistenceError) {
            return persistenceError;
        }
    }

    private RuntimeSessionHolder requireEngine(RuntimeSessionDTO session) {
        return engineRegistry.find(session.getId()).orElseGet(() -> {
            var snapshot = snapshotProvider.resolveRevision(session.getAgentId(), session.getBundleRevision());
            var model = modelManager.resolveModel(snapshot, session.getModelId());
            var entries = repository.listCurrentBranch(session.getId(), 0, RESTORE_LIMIT);
            var messages = codec.toAgentMessages(session.getId(), entries, model, fileResolver);
            return engineRegistry.restore(session.getId(), snapshot, model, session.isThinking(), messages);
        });
    }

    private RuntimeSessionDTO requireOwnedSession(String sessionId, CallerAuthContext caller, boolean submitOperation) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));
        if (!session.getOwnerId().equals(caller.callerId())) {
            String chinese = submitOperation ? "当前调用方无权向该 Session 提交用户事件。" : "当前调用方无权读取该 Session 的持久化事件。";
            String english = submitOperation
                    ? "The caller is not allowed to submit an event to this Session."
                    : "The caller is not allowed to read this Session's persisted events.";
            throw new RuntimeApiException(HttpStatus.FORBIDDEN, RuntimeErrorCode.FORBIDDEN, chinese, english);
        }
        return session;
    }

    private ValidatedUserEvent validate(UserEventRequestVO request) {
        if (request == null || !validator.validate(request).isEmpty()) {
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

    private UserMessage toUserMessage(String message, List<ContentBlock> fileContent) {
        List<ContentBlock> content = new ArrayList<>();
        if (message != null) {
            content.add(new TextContent(message));
        }
        content.addAll(fileContent);
        return new UserMessage(List.copyOf(content), clock.millis());
    }

    private static void requireAccepted(UserEventAcceptance acceptance) {
        switch (acceptance.status()) {
            case ACCEPTED -> {}
            case NOT_FOUND -> throw new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND);
            case FORBIDDEN ->
                throw new RuntimeApiException(
                        HttpStatus.FORBIDDEN,
                        RuntimeErrorCode.FORBIDDEN,
                        "当前调用方无权向该 Session 提交用户事件。",
                        "The caller is not allowed to submit an event to this Session.");
            case BUSY -> throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_BUSY);
        }
    }

    private static int parseLimit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return limit;
        } catch (NumberFormatException error) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_EVENT_LIST_QUERY, error);
        }
    }

    private static RuntimeApiException invalidEventRequest() {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_EVENT_REQUEST);
    }

    private static RuntimeApiException mapAcceptanceError(RuntimeException error) {
        if (error instanceof RuntimeApiException apiError) {
            return apiError;
        }
        return new RuntimeApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED, error);
    }

    private static void emitStreamError(RuntimeEventStream stream, boolean chinese) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("resCode", RuntimeErrorCode.SESSION_EXECUTION_FAILED.name());
        data.put("resMsg", RuntimeErrorCode.SESSION_EXECUTION_FAILED.message(chinese));
        stream.emit(new RuntimeSseEventVO(null, "stream.error", data));
    }

    private static void emitSuccessfulEnd(RuntimeEventStream stream, StopReason reason) {
        stream.emit(new RuntimeSseEventVO(null, "session.status.idle", java.util.Map.of("status", "idle")));
        String value = reason == StopReason.ABORTED ? "aborted" : "completed";
        stream.emit(new RuntimeSseEventVO(null, "stream.end", java.util.Map.of("reason", value)));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record ValidatedUserEvent(String message, List<String> fileIds) {}
}
