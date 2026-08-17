/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.List;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanFactory;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionConfigurationService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

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
 * Session 模型列表、模型切换与深度思考开关的函数式 Controller。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeSessionConfigurationController {
    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeSessionConfigurationService service;

    public RuntimeSessionConfigurationController(RuntimeSessionConfigurationService service) {
        this.service = service;
    }

    public Mono<ServerResponse> listModels(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        return Mono.fromCallable(() -> service.listModels(sessionId, RuntimeRequestContext.auth(request)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> success(request, result));
    }

    public Mono<ServerResponse> changeModel(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        String ifMatch = ifMatch(request);
        return request.bodyToMono(ChangeModelRequestVO.class)
                .onErrorMap(error -> invalidBody(error, RuntimeErrorCode.INVALID_MODEL_REQUEST))
                .switchIfEmpty(Mono.error(invalidRequest(RuntimeErrorCode.INVALID_MODEL_REQUEST)))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(body -> serviceResponse(
                        request, service.changeModel(sessionId, RuntimeRequestContext.auth(request), ifMatch, body)));
    }

    public Mono<ServerResponse> changeThinking(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        String ifMatch = ifMatch(request);
        return request.bodyToMono(ChangeThinkingRequestVO.class)
                .onErrorMap(error -> invalidBody(error, RuntimeErrorCode.INVALID_THINKING_REQUEST))
                .switchIfEmpty(Mono.error(invalidRequest(RuntimeErrorCode.INVALID_THINKING_REQUEST)))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(body -> serviceResponse(
                        request,
                        service.changeThinking(sessionId, RuntimeRequestContext.auth(request), ifMatch, body)));
    }

    private Mono<ServerResponse> success(ServerRequest request, Object result) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("Content-Language", RuntimeRequestContext.language(request))
                .bodyValue(ResultBeanFactory.getFactory().normal(result));
    }

    private Mono<ServerResponse> serviceResponse(ServerRequest request, RuntimeSessionView<GetSessionResponseVO> view) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("Content-Language", RuntimeRequestContext.language(request))
                .eTag(view.etag())
                .bodyValue(ResultBeanFactory.getFactory().normal(view.resource()));
    }

    private static String ifMatch(ServerRequest request) {
        List<String> values = request.headers().header("If-Match");
        return values.isEmpty() ? null : String.join(",", values);
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
