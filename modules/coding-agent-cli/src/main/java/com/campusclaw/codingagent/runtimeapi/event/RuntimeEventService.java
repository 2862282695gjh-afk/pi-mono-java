/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.vo.EventPageResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;

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

    private static final int RESTORE_BATCH_SIZE = 500;

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator idGenerator;

    private final AgentDirectoryResolver agentDirectoryResolver;

    private final RuntimeModelManager modelManager;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeExecutionTimeoutScheduler timeoutScheduler;

    private final RuntimeEventCursorCodec cursorCodec;

    private final RuntimeEventProperties eventProperties;

    private final RuntimeExecutionProperties executionProperties;

    private final Validator validator;

    private final MessageSource messageSource;

    private final Clock clock;

    public RuntimeEventService(
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator idGenerator,
            AgentDirectoryResolver agentDirectoryResolver,
            RuntimeModelManager modelManager,
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeExecutionTimeoutScheduler timeoutScheduler,
            RuntimeEventCursorCodec cursorCodec,
            RuntimeEventProperties eventProperties,
            RuntimeExecutionProperties executionProperties,
            Validator validator,
            MessageSource messageSource,
            Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.idGenerator = idGenerator;
        this.agentDirectoryResolver = agentDirectoryResolver;
        this.modelManager = modelManager;
        this.engineRegistry = engineRegistry;
        this.timeoutScheduler = timeoutScheduler;
        this.cursorCodec = cursorCodec;
        this.eventProperties = eventProperties;
        this.executionProperties = executionProperties;
        this.validator = validator;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    public RuntimeEventStream submit(String sessionId, UserEventRequestVO request, boolean chinese) {
        try {
            return prepareAndSubmit(sessionId, validate(request), chinese);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED, error);
        }
    }

    public EventPageResponseVO list(String sessionId, String limitValue, String page) {
        try {
            int limit = parseLimit(limitValue);
            requireSession(sessionId);
            long afterSeq = page == null ? 0 : cursorCodec.decode(page, sessionId);
            List<RuntimeEntryDTO> entries = repository.listCurrentBranch(sessionId, afterSeq, limit + 1);
            return pageOf(sessionId, entries, limit);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.EVENT_LIST_FAILED, error);
        }
    }

    private RuntimeEventStream prepareAndSubmit(
            String sessionId, ValidatedUserEvent request, boolean chinese) {
        engineRegistry.lockOperation(sessionId);
        RuntimeSessionHolder holder = null;
        RuntimeActiveExecution execution = null;
        try {
            RuntimeSessionDTO session = requireIdleSession(sessionId);
            var snapshot = agentDirectoryResolver.resolve(session.getAgentId());
            Model model = modelManager.resolveModel(snapshot, session.getModelId());
            List<Message> history = restoreHistory(sessionId, model);
            UserMessage message = codec.toUserMessage(request.message(), request.fileIds(), clock.millis());
            execution = new RuntimeActiveExecution(newEventStream());
            holder = engineRegistry.register(
                    sessionId, snapshot, model, session.isThinking(), history, execution);
            acceptUserEntry(sessionId, request, execution);
            startAgent(holder, execution, message, chinese);
            return execution.eventStream();
        } catch (RuntimeException error) {
            releaseUnacceptedExecution(holder, execution);
            throw error;
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private void acceptUserEntry(
            String sessionId, ValidatedUserEvent request, RuntimeActiveExecution execution) {
        RuntimeEntryDTO entry = codec.userEntry(
                sessionId, idGenerator.nextId(), request.message(), request.fileIds(), now());
        UserEventAcceptance acceptance = repository.acceptUserEvent(sessionId, entry, now());
        requireAccepted(acceptance);
        execution.eventStream().emit(new RuntimeSseEventVO(
                Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
    }

    private void startAgent(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            UserMessage message,
            boolean chinese) {
        RuntimeEventProjector projector = new RuntimeEventProjector(
                holder.sessionId(),
                repository,
                codec,
                idGenerator,
                execution.eventStream(),
                clock,
                holder.agent()::abort,
                execution,
                message);
        Runnable unsubscribe = () -> {};
        try {
            unsubscribe = holder.agent().subscribe(projector::onEvent);
            scheduleTimeout(holder, execution);
            CompletableFuture<Void> future = holder.agent().prompt(message);
            Runnable finalUnsubscribe = unsubscribe;
            future.whenComplete(
                    (unused, error) -> finishAgent(holder, execution, projector, finalUnsubscribe, error, chinese));
        } catch (RuntimeException error) {
            finishAgent(holder, execution, projector, unsubscribe, error, chinese);
        }
    }

    private void scheduleTimeout(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        var task = timeoutScheduler.schedule(
                () -> timeoutExecution(holder, execution), executionProperties.getMaxDuration());
        execution.setTimeoutTask(task);
    }

    private void timeoutExecution(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        if (!holder.activeExecution().filter(active -> active == execution).isPresent()) {
            return;
        }
        execution.requestTimeout();
        holder.agent().clearSteeringQueue();
        holder.agent().clearFollowUpQueue();
        holder.agent().abort();
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
            if (!continueQueuedExecution(holder, execution, projector, executionError, chinese, unsubscribe)) {
                completeExecution(holder, execution, projector, unsubscribe, executionError, chinese);
            }
        } finally {
            engineRegistry.unlockOperation(holder.sessionId());
        }
    }

    private boolean continueQueuedExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Throwable executionError,
            boolean chinese,
            Runnable unsubscribe) {
        if (!canContinue(holder, execution, projector, executionError)) {
            return false;
        }
        try {
            holder.agent()
                    .continueQueuedExecution()
                    .whenComplete((unused, error) ->
                            finishAgent(holder, execution, projector, unsubscribe, error, chinese));
        } catch (RuntimeException error) {
            completeExecution(holder, execution, projector, unsubscribe, error, chinese);
        }
        return true;
    }

    private static boolean canContinue(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Throwable executionError) {
        return executionError == null
                && projector.failure() == null
                && projector.terminalReason() != StopReason.ERROR
                && projector.terminalReason() != StopReason.ABORTED
                && execution.acceptingControls()
                && holder.agent().hasQueuedControlMessages();
    }

    private void completeExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            Runnable unsubscribe,
            Throwable executionError,
            boolean chinese) {
        execution.closeControls();
        Throwable failure = executionFailure(execution, executionError, projector);
        failure = finishPersistence(holder.sessionId(), failure);
        failure = releaseExecution(holder, execution, unsubscribe, failure);
        emitTerminalEvents(execution.eventStream(), execution, projector.terminalReason(), failure, chinese);
        execution.eventStream().complete();
        execution.complete(failure);
    }

    private static Throwable executionFailure(
            RuntimeActiveExecution execution, Throwable executionError, RuntimeEventProjector projector) {
        if (execution.timedOut()) {
            return new TimeoutException("runtime execution exceeded its maximum duration");
        }
        return executionError != null ? executionError : projector.failure();
    }

    private Throwable finishPersistence(String sessionId, Throwable failure) {
        try {
            repository.finishExecution(sessionId, now());
            return failure;
        } catch (RuntimeException persistenceError) {
            return persistenceError;
        }
    }

    private Throwable releaseExecution(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            Runnable unsubscribe,
            Throwable failure) {
        Throwable result = failure;
        try {
            unsubscribe.run();
        } catch (RuntimeException unsubscribeError) {
            result = result != null ? result : unsubscribeError;
        } finally {
            engineRegistry.complete(holder, execution);
        }
        return result;
    }

    private void emitTerminalEvents(
            RuntimeEventStream stream,
            RuntimeActiveExecution execution,
            StopReason reason,
            Throwable failure,
            boolean chinese) {
        if (failure != null || reason == StopReason.ERROR || execution.timedOut()) {
            emitStreamError(stream, chinese);
            return;
        }
        stream.emit(new RuntimeSseEventVO(null, "session.status.idle", java.util.Map.of("status", "idle")));
        String value = execution.abortRequested() || reason == StopReason.ABORTED ? "aborted" : "completed";
        stream.emit(new RuntimeSseEventVO(null, "stream.end", java.util.Map.of("reason", value)));
    }

    private void emitStreamError(RuntimeEventStream stream, boolean chinese) {
        RuntimeErrorCode code = RuntimeErrorCode.SESSION_EXECUTION_FAILED;
        Locale locale = chinese ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("resCode", code.name());
        data.put("resMsg", messageSource.getMessage(code.messageKey(), null, locale));
        stream.emit(new RuntimeSseEventVO(null, "stream.error", data));
    }

    private List<Message> restoreHistory(String sessionId, Model model) {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        long afterSeq = 0L;
        while (true) {
            List<RuntimeEntryDTO> batch = repository.listCurrentBranch(
                    sessionId, afterSeq, RESTORE_BATCH_SIZE);
            entries.addAll(batch);
            if (batch.size() < RESTORE_BATCH_SIZE) {
                break;
            }
            afterSeq = batch.getLast().getEntrySeq();
        }
        return codec.toAgentMessages(entries, model);
    }

    private EventPageResponseVO pageOf(String sessionId, List<RuntimeEntryDTO> entries, int limit) {
        boolean more = entries.size() > limit;
        List<RuntimeEntryDTO> pageEntries = more ? entries.subList(0, limit) : entries;
        List<java.util.Map<String, Object>> events = pageEntries.stream()
                .map(codec::toHistoryEvent)
                .toList();
        String nextPage = more ? cursorCodec.encode(sessionId, pageEntries.getLast().getEntrySeq()) : null;
        return new EventPageResponseVO(events, nextPage);
    }

    private RuntimeEventStream newEventStream() {
        return new RuntimeEventStream(
                eventProperties.getStreamBufferEvents(),
                eventProperties.getStreamBufferBytes(),
                eventProperties.getHeartbeatInterval(),
                codec::encodedSseBytes);
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));
    }

    private RuntimeSessionDTO requireIdleSession(String sessionId) {
        RuntimeSessionDTO session = requireSession(sessionId);
        if (!"idle".equals(session.getState())) {
            throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_BUSY);
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

    private static void requireAccepted(UserEventAcceptance acceptance) {
        switch (acceptance.status()) {
            case ACCEPTED -> {}
            case NOT_FOUND -> throw new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_BUSY);
        }
    }

    private void releaseUnacceptedExecution(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        if (holder != null && execution != null) {
            engineRegistry.complete(holder, execution);
            execution.eventStream().complete();
            execution.complete(null);
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

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record ValidatedUserEvent(String message, List<String> fileIds) {}
}
