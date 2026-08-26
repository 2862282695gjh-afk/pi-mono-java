/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.Locale;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.vo.ErrorResponseVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Runtime HTTP V1 的统一异常与国际化响应处理器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@RestControllerAdvice(basePackages = "com.campusclaw.codingagent.runtimeapi.web")
public class RuntimeExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(RuntimeExceptionHandler.class);

    private final MessageSource messageSource;

    public RuntimeExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(RuntimeApiException.class)
    public ResponseEntity<ErrorResponseVO> handleRuntimeError(RuntimeApiException error, HttpServletRequest request) {
        return response(error.errorCode(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponseVO> handleInvalidBody(Exception error, HttpServletRequest request) {
        RuntimeErrorCode errorCode = classifyInvalidBody(request);
        log.atWarn()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.http.body.validate")
                .addKeyValue("errorCode", errorCode.name())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .setCause(error)
                .log("CampusClaw failure: operation={}, errorCode={}", "runtime.http.body.validate", errorCode.name());
        return response(errorCode, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponseVO> handleInvalidParameter(
            HandlerMethodValidationException error, HttpServletRequest request) {
        RuntimeErrorCode errorCode = classifyInvalidParameter(error);
        log.atWarn()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.http.parameter.validate")
                .addKeyValue("errorCode", errorCode.name())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .setCause(error)
                .log(
                        "CampusClaw failure: operation={}, errorCode={}",
                        "runtime.http.parameter.validate",
                        errorCode.name());
        return response(errorCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseVO> handleUnexpectedError(Exception error, HttpServletRequest request) {
        log.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.http.request")
                .addKeyValue("errorCode", RuntimeErrorCode.INTERNAL_ERROR.name())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .setCause(error)
                .log(
                        "CampusClaw failure: operation={}, errorCode={}",
                        "runtime.http.request",
                        RuntimeErrorCode.INTERNAL_ERROR.name());
        return response(RuntimeErrorCode.INTERNAL_ERROR, request);
    }

    private ResponseEntity<ErrorResponseVO> response(RuntimeErrorCode errorCode, HttpServletRequest request) {
        Locale locale = RuntimeRequestContext.locale(request);
        String message = messageSource.getMessage(errorCode.messageKey(), null, locale);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag());
        errorCode
                .retryAfterSeconds()
                .ifPresent(seconds -> headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(seconds)));
        return new ResponseEntity<>(new ErrorResponseVO(errorCode.name(), message), headers, errorCode.status());
    }

    private static RuntimeErrorCode classifyInvalidBody(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = value instanceof String path ? path : "";
        return switch (pattern) {
            case "/campusclaw-service/v1/sessions/{sessionId}/events" -> RuntimeErrorCode.INVALID_EVENT_REQUEST;
            case "/campusclaw-service/v1/sessions/{sessionId}/model" -> RuntimeErrorCode.INVALID_MODEL_REQUEST;
            case "/campusclaw-service/v1/sessions/{sessionId}/thinking" -> RuntimeErrorCode.INVALID_THINKING_REQUEST;
            case "/campusclaw-service/v1/sessions/{sessionId}/steers" -> RuntimeErrorCode.INVALID_STEER_REQUEST;
            case "/campusclaw-service/v1/sessions/{sessionId}/follow-ups" -> RuntimeErrorCode.INVALID_FOLLOW_UP_REQUEST;
            default -> RuntimeErrorCode.INTERNAL_ERROR;
        };
    }

    private static RuntimeErrorCode classifyInvalidParameter(HandlerMethodValidationException error) {
        return error.getParameterValidationResults().stream()
                .map(result -> result.getMethodParameter().getParameterAnnotation(PathVariable.class))
                .filter(annotation -> annotation != null)
                .map(RuntimeExceptionHandler::pathVariableName)
                .map(RuntimeExceptionHandler::identifierErrorCode)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(RuntimeErrorCode.INTERNAL_ERROR);
    }

    private static String pathVariableName(PathVariable annotation) {
        return annotation.value().isBlank() ? annotation.name() : annotation.value();
    }

    private static Optional<RuntimeErrorCode> identifierErrorCode(String pathVariableName) {
        return switch (pathVariableName) {
            case "agentId" -> Optional.of(RuntimeErrorCode.INVALID_AGENT_ID);
            case "sessionId" -> Optional.of(RuntimeErrorCode.INVALID_SESSION_ID);
            default -> Optional.empty();
        };
    }
}
