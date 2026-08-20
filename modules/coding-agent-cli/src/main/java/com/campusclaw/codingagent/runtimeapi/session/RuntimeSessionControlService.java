/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;

import org.springframework.stereotype.Service;

/**
 * Session 当前执行的 Steer、FollowUp 与 Abort 控制业务。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionControlService {
    private final RuntimeSessionRepository repository;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final Clock clock;

    private final RuntimeExecutionProperties properties;

    public RuntimeSessionControlService(
            RuntimeSessionRepository repository,
            RuntimeSessionEngineRegistry engineRegistry,
            Clock clock,
            RuntimeExecutionProperties properties) {
        this.repository = repository;
        this.engineRegistry = engineRegistry;
        this.clock = clock;
        this.properties = properties;
    }

    public ControlMessageAcceptedResponseVO steer(String sessionId, ControlMessageRequestVO request) {
        return accept(sessionId, request, ControlKind.STEER);
    }

    public ControlMessageAcceptedResponseVO followUp(String sessionId, ControlMessageRequestVO request) {
        return accept(sessionId, request, ControlKind.FOLLOW_UP);
    }

    public void abort(String sessionId) {
        try {
            prepareAbort(sessionId).join();
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_ABORT_FAILED, error);
        }
    }

    private ControlMessageAcceptedResponseVO accept(
            String sessionId, ControlMessageRequestVO request, ControlKind kind) {
        requireRequest(request, kind.invalidRequest());
        engineRegistry.lockOperation(sessionId);
        try {
            RuntimeSessionDTO session = requireSession(sessionId);
            RuntimeSessionHolder holder = requireRunningHolder(session);
            RuntimeActiveExecution execution = requireAcceptingExecution(holder);
            UserMessage message = new UserMessage(request.getMessage(), clock.millis());
            long bytes = request.getMessage().getBytes(StandardCharsets.UTF_8).length;
            queueControl(holder, execution, message, bytes, kind);
            return new ControlMessageAcceptedResponseVO(sessionId, now());
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(kind.acceptanceFailed(), error);
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private CompletableFuture<Void> prepareAbort(String sessionId) {
        engineRegistry.lockOperation(sessionId);
        try {
            RuntimeSessionDTO session = requireSession(sessionId);
            if (RuntimeSessionState.IDLE.matches(session.getState())) {
                engineRegistry.find(sessionId).ifPresent(this::clearControlQueues);
                return CompletableFuture.completedFuture(null);
            }
            RuntimeSessionHolder holder = requireRunningHolder(session);
            RuntimeActiveExecution execution = holder.activeExecution()
                    .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_EXECUTION_UNAVAILABLE));
            execution.requestAbort();
            clearControlQueues(holder);
            holder.agent().abort();
            return execution.completion();
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
    }

    private RuntimeSessionHolder requireRunningHolder(RuntimeSessionDTO session) {
        if (!RuntimeSessionState.RUNNING.matches(session.getState())) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_RUNNING);
        }
        return engineRegistry
                .find(session.getId())
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_EXECUTION_UNAVAILABLE));
    }

    private static RuntimeActiveExecution requireAcceptingExecution(RuntimeSessionHolder holder) {
        RuntimeActiveExecution execution = holder.activeExecution()
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_RUNNING));
        if (!execution.acceptingControls()) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_RUNNING);
        }
        return execution;
    }

    private void queueControl(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            UserMessage message,
            long bytes,
            ControlKind kind) {
        if (!execution.queueControl(
                message, bytes, properties.getMaxControlMessages(), properties.getMaxControlBytes())) {
            throw new RuntimeApiException(RuntimeErrorCode.CONTROL_QUEUE_FULL);
        }
        try {
            if (kind == ControlKind.STEER) {
                holder.agent().steer(message);
            } else {
                holder.agent().followUp(message);
            }
        } catch (RuntimeException error) {
            execution.removeQueuedControl(message);
            throw error;
        }
    }

    private static void requireRequest(ControlMessageRequestVO request, RuntimeErrorCode errorCode) {
        if (request == null
                || request.getMessage() == null
                || request.getMessage().isBlank()
                || request.getMessage().length() > RuntimeApiConstants.MAX_MESSAGE_CHARACTERS) {
            throw new RuntimeApiException(errorCode);
        }
    }

    private void clearControlQueues(RuntimeSessionHolder holder) {
        holder.agent().clearSteeringQueue();
        holder.agent().clearFollowUpQueue();
        holder.activeExecution().ifPresent(RuntimeActiveExecution::clearQueuedControls);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private enum ControlKind {
        STEER(RuntimeErrorCode.INVALID_STEER_REQUEST, RuntimeErrorCode.STEER_ACCEPTANCE_FAILED),
        FOLLOW_UP(RuntimeErrorCode.INVALID_FOLLOW_UP_REQUEST, RuntimeErrorCode.FOLLOW_UP_ACCEPTANCE_FAILED);

        private final RuntimeErrorCode invalidRequest;

        private final RuntimeErrorCode acceptanceFailed;

        ControlKind(RuntimeErrorCode invalidRequest, RuntimeErrorCode acceptanceFailed) {
            this.invalidRequest = invalidRequest;
            this.acceptanceFailed = acceptanceFailed;
        }

        RuntimeErrorCode invalidRequest() {
            return invalidRequest;
        }

        RuntimeErrorCode acceptanceFailed() {
            return acceptanceFailed;
        }
    }
}
