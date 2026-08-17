/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;

/**
 * Session 当前执行的 Steer、FollowUp 与 Abort 控制业务。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeSessionControlService {
    private final RuntimeSessionRepository repository;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final Validator validator;

    private final Clock clock;

    public RuntimeSessionControlService(
            RuntimeSessionRepository repository,
            RuntimeSessionEngineRegistry engineRegistry,
            Validator validator,
            Clock clock) {
        this.repository = repository;
        this.engineRegistry = engineRegistry;
        this.validator = validator;
        this.clock = clock;
    }

    public ControlMessageAcceptedResponseVO steer(
            String sessionId, CallerAuthContext caller, ControlMessageRequestVO request) {
        return accept(sessionId, caller, request, ControlKind.STEER);
    }

    public ControlMessageAcceptedResponseVO followUp(
            String sessionId, CallerAuthContext caller, ControlMessageRequestVO request) {
        return accept(sessionId, caller, request, ControlKind.FOLLOW_UP);
    }

    public void abort(String sessionId, CallerAuthContext caller) {
        try {
            prepareAbort(sessionId, caller).join();
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_ABORT_FAILED, error);
        }
    }

    private ControlMessageAcceptedResponseVO accept(
            String sessionId, CallerAuthContext caller, ControlMessageRequestVO request, ControlKind kind) {
        requireValid(request, kind.invalidRequest());
        engineRegistry.lockOperation(sessionId);
        try {
            RuntimeSessionDTO session = requireOwnedSession(sessionId, caller);
            RuntimeSessionHolder holder = requireRunningHolder(session);
            requireAcceptingExecution(holder);
            UserMessage message = new UserMessage(request.getMessage(), clock.millis());
            if (kind == ControlKind.STEER) {
                holder.agent().steer(message);
            } else {
                holder.agent().followUp(message);
            }
            return new ControlMessageAcceptedResponseVO(sessionId, now());
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(HttpStatus.INTERNAL_SERVER_ERROR, kind.acceptanceFailed(), error);
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private CompletableFuture<Void> prepareAbort(String sessionId, CallerAuthContext caller) {
        engineRegistry.lockOperation(sessionId);
        try {
            RuntimeSessionDTO session = requireOwnedSession(sessionId, caller);
            if ("idle".equals(session.getState())) {
                engineRegistry.find(sessionId).ifPresent(this::clearControlQueues);
                return CompletableFuture.completedFuture(null);
            }
            RuntimeSessionHolder holder = requireRunningHolder(session);
            RuntimeActiveExecution execution = holder.activeExecution()
                    .orElseThrow(() -> new IllegalStateException("running Session has no active execution"));
            execution.requestAbort();
            clearControlQueues(holder);
            holder.agent().abort();
            return execution.completion();
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private RuntimeSessionDTO requireOwnedSession(String sessionId, CallerAuthContext caller) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));
        if (!session.getOwnerId().equals(caller.callerId())) {
            throw new RuntimeApiException(
                    HttpStatus.FORBIDDEN,
                    RuntimeErrorCode.FORBIDDEN,
                    "当前调用方无权控制该 Session。",
                    "The caller is not allowed to control this Session.");
        }
        return session;
    }

    private RuntimeSessionHolder requireRunningHolder(RuntimeSessionDTO session) {
        if (!"running".equals(session.getState())) {
            throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_NOT_RUNNING);
        }
        return engineRegistry
                .find(session.getId())
                .orElseThrow(() -> new IllegalStateException("running Session has no in-memory engine"));
    }

    private static RuntimeActiveExecution requireAcceptingExecution(RuntimeSessionHolder holder) {
        RuntimeActiveExecution execution = holder.activeExecution()
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_NOT_RUNNING));
        if (!execution.acceptingControls()) {
            throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_NOT_RUNNING);
        }
        return execution;
    }

    private void requireValid(ControlMessageRequestVO request, RuntimeErrorCode errorCode) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, errorCode);
        }
    }

    private void clearControlQueues(RuntimeSessionHolder holder) {
        holder.agent().clearSteeringQueue();
        holder.agent().clearFollowUpQueue();
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
