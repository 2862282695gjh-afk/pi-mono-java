/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.Locale;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.vo.ErrorResponseVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Runtime HTTP V1 的统一异常与国际化响应处理器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@RestControllerAdvice(basePackages = "com.campusclaw.codingagent.runtimeapi.web")
public class RuntimeExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(RuntimeExceptionHandler.class);

    private final MessageSource messageSource;

    public RuntimeExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(RuntimeApiException.class)
    public ResponseEntity<ErrorResponseVO> handleRuntimeError(
            RuntimeApiException error, HttpServletRequest request) {
        logServerError(error, request);
        return response(error.status(), error.errorCode(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponseVO> handleInvalidBody(Exception error, HttpServletRequest request) {
        log.debug("Invalid Runtime request body: {} {}", request.getMethod(), request.getRequestURI(), error);
        return response(HttpStatus.BAD_REQUEST, classifyInvalidBody(request), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseVO> handleUnexpectedError(Exception error, HttpServletRequest request) {
        log.error("Runtime API request failed: {} {}", request.getMethod(), request.getRequestURI(), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.INTERNAL_ERROR, request);
    }

    private ResponseEntity<ErrorResponseVO> response(
            HttpStatus status, RuntimeErrorCode errorCode, HttpServletRequest request) {
        boolean chinese = RuntimeRequestContext.chinese(request);
        Locale locale = chinese ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        String message = messageSource.getMessage(errorCode.messageKey(), null, locale);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_LANGUAGE, chinese ? "zh-CN" : "en-US");
        if (requiresRetryAfter(errorCode)) {
            headers.set(HttpHeaders.RETRY_AFTER, "3");
        }
        return new ResponseEntity<>(new ErrorResponseVO(errorCode.name(), message), headers, status);
    }

    private static RuntimeErrorCode classifyInvalidBody(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.endsWith("/events")) {
            return RuntimeErrorCode.INVALID_EVENT_REQUEST;
        }
        if (path.endsWith("/model")) {
            return RuntimeErrorCode.INVALID_MODEL_REQUEST;
        }
        if (path.endsWith("/thinking")) {
            return RuntimeErrorCode.INVALID_THINKING_REQUEST;
        }
        if (path.endsWith("/steers")) {
            return RuntimeErrorCode.INVALID_STEER_REQUEST;
        }
        return RuntimeErrorCode.INVALID_FOLLOW_UP_REQUEST;
    }

    private static boolean requiresRetryAfter(RuntimeErrorCode errorCode) {
        return errorCode == RuntimeErrorCode.MANAGER_UNAVAILABLE
                || errorCode == RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED;
    }

    private static void logServerError(RuntimeApiException error, HttpServletRequest request) {
        if (error.status().is5xxServerError()) {
            log.error("Runtime API request failed: {} {}", request.getMethod(), request.getRequestURI(), error);
        }
    }
}
