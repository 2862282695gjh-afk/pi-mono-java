/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.controlplane.error;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把控制面 MVC 异常转换为稳定的结构化 HTTP 错误响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@RestControllerAdvice(basePackages = "com.huawei.hicampus.claw.codingagent.controlplane.api")
public class ControlPlaneExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ControlPlaneExceptionHandler.class);

    private final Clock clock;

    public ControlPlaneExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException error) {
        log.warn("resource not found: {}", error.getMessage());
        return errorBody(HttpStatus.NOT_FOUND, error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        log.warn("bad request: {}", error.getMessage());
        return errorBody(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException error) {
        FieldError field = error.getBindingResult().getFieldError();
        String message = field == null ? "request body is invalid" : field.getField() + " " + field.getDefaultMessage();
        log.warn("bad request: {}", message);
        return errorBody(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException error) {
        Throwable invalid = findCause(error, IllegalArgumentException.class);
        String message = invalid == null ? "request body is required" : invalid.getMessage();
        log.warn("bad request: {}", message);
        return errorBody(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
        log.error("unexpected control-plane error", error);
        return errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now(clock).toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }

    private static Throwable findCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
