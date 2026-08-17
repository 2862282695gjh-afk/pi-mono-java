/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanFactory;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionControlService;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;

import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Session Steer、FollowUp 与 Abort 的函数式 Controller。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeSessionControlController {
    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeSessionControlService service;

    public RuntimeSessionControlController(RuntimeSessionControlService service) {
        this.service = service;
    }

    public Mono<ServerResponse> steer(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        return request.bodyToMono(ControlMessageRequestVO.class)
                .onErrorMap(error -> invalidBody(error, RuntimeErrorCode.INVALID_STEER_REQUEST))
                .switchIfEmpty(Mono.error(invalidRequest(RuntimeErrorCode.INVALID_STEER_REQUEST)))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(
                        body -> accepted(request, service.steer(sessionId, RuntimeRequestContext.auth(request), body)));
    }

    public Mono<ServerResponse> followUp(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        return request.bodyToMono(ControlMessageRequestVO.class)
                .onErrorMap(error -> invalidBody(error, RuntimeErrorCode.INVALID_FOLLOW_UP_REQUEST))
                .switchIfEmpty(Mono.error(invalidRequest(RuntimeErrorCode.INVALID_FOLLOW_UP_REQUEST)))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(body ->
                        accepted(request, service.followUp(sessionId, RuntimeRequestContext.auth(request), body)));
    }

    public Mono<ServerResponse> abort(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        return Mono.fromRunnable(() -> service.abort(sessionId, RuntimeRequestContext.auth(request)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(ServerResponse.noContent()
                        .header("Cache-Control", "no-store")
                        .build());
    }

    private Mono<ServerResponse> accepted(ServerRequest request, ControlMessageAcceptedResponseVO result) {
        return ServerResponse.accepted()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("Content-Language", RuntimeRequestContext.language(request))
                .bodyValue(ResultBeanFactory.getFactory().normal(result));
    }

    private static Throwable invalidBody(Throwable error, RuntimeErrorCode errorCode) {
        if (error instanceof RuntimeApiException) {
            return error;
        }
        if (error instanceof DecodingException
                || error instanceof UnsupportedMediaTypeException
                || error instanceof ServerWebInputException
                || error instanceof IllegalArgumentException) {
            return invalidRequest(errorCode);
        }
        return error;
    }

    private static String requireSessionId(String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_SESSION_ID);
        }
        return sessionId;
    }

    private static RuntimeApiException invalidRequest(RuntimeErrorCode errorCode) {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, errorCode);
    }
}
