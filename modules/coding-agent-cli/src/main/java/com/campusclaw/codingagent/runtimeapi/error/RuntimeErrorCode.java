/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.error;

import java.util.OptionalInt;

import org.springframework.http.HttpStatus;

/**
 * Runtime HTTP V1 的稳定错误码、HTTP 状态和重试语义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
public enum RuntimeErrorCode {
    INVALID_AGENT_ID(HttpStatus.BAD_REQUEST),
    INVALID_SESSION_ID(HttpStatus.BAD_REQUEST),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    AGENT_MODEL_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_ENTITY),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    SESSION_BUSY(HttpStatus.CONFLICT),
    SESSION_INITIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    SESSION_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    SESSION_NAME_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_COMMAND_REQUEST(HttpStatus.BAD_REQUEST),
    COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND),
    COMMAND_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_EVENT_REQUEST(HttpStatus.BAD_REQUEST),
    EVENT_ACCEPTANCE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    EVENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 3),
    SESSION_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_EVENT_LIST_QUERY(HttpStatus.BAD_REQUEST),
    EVENT_LIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_MODEL_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_THINKING_REQUEST(HttpStatus.BAD_REQUEST),
    MODEL_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    THINKING_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY),
    IF_MATCH_REQUIRED(HttpStatus.PRECONDITION_REQUIRED),
    SESSION_VERSION_MISMATCH(HttpStatus.PRECONDITION_FAILED),
    SESSION_MODEL_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    SESSION_THINKING_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    SESSION_NOT_RUNNING(HttpStatus.CONFLICT),
    INTERRUPT_TARGET_MISMATCH(HttpStatus.CONFLICT),
    INTERRUPT_ALREADY_REQUESTED(HttpStatus.CONFLICT),
    TOOL_CONFIRMATION_NOT_PENDING(HttpStatus.CONFLICT),
    EXECUTION_STOPPING(HttpStatus.CONFLICT),
    RUNTIME_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, 3),
    MANAGER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 3),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    private final Integer retryAfterSeconds;

    RuntimeErrorCode(HttpStatus status) {
        this(status, null);
    }

    RuntimeErrorCode(HttpStatus status, Integer retryAfterSeconds) {
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return name();
    }

    public OptionalInt retryAfterSeconds() {
        return retryAfterSeconds == null ? OptionalInt.empty() : OptionalInt.of(retryAfterSeconds);
    }
}
