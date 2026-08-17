/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.Map;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanFactory;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;

import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Session Events 的 POST SSE 和 GET 历史分页函数式 Controller。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeEventController {
    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeEventService service;

    public RuntimeEventController(RuntimeEventService service) {
        this.service = service;
    }

    public Mono<ServerResponse> submit(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        Mono<UserEventRequestVO> body = request.bodyToMono(UserEventRequestVO.class)
                .onErrorMap(this::invalidBody)
                .switchIfEmpty(Mono.error(invalidEventRequest()));
        return body.publishOn(Schedulers.boundedElastic()).flatMap(event -> {
            Flux<ServerSentEvent<Map<String, Object>>> events = service.submit(
                            sessionId,
                            RuntimeRequestContext.auth(request),
                            event,
                            RuntimeRequestContext.chinese(request))
                    .map(RuntimeEventController::toServerSentEvent);
            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("Cache-Control", "no-store")
                    .header("Content-Language", RuntimeRequestContext.language(request))
                    .body(BodyInserters.fromServerSentEvents(events));
        });
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        String sessionId = requireSessionId(request.pathVariable("session_id"));
        String limit = request.queryParam("limit").orElse(null);
        String page = request.queryParam("page").orElse(null);
        return Mono.fromCallable(() -> service.list(sessionId, RuntimeRequestContext.auth(request), limit, page))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Cache-Control", "no-store")
                        .header("Content-Language", RuntimeRequestContext.language(request))
                        .bodyValue(ResultBeanFactory.getFactory().normal(result)));
    }

    private Throwable invalidBody(Throwable error) {
        if (error instanceof RuntimeApiException) {
            return error;
        }
        if (error instanceof DecodingException
                || error instanceof UnsupportedMediaTypeException
                || error instanceof ServerWebInputException
                || error instanceof IllegalArgumentException) {
            return invalidEventRequest();
        }
        return error;
    }

    private static ServerSentEvent<Map<String, Object>> toServerSentEvent(RuntimeSseEventVO event) {
        ServerSentEvent.Builder<Map<String, Object>> builder =
                ServerSentEvent.builder(event.getData()).event(event.getEvent());
        if (event.getId() != null) {
            builder.id(event.getId());
        }
        return builder.build();
    }

    private static String requireSessionId(String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_SESSION_ID);
        }
        return sessionId;
    }

    private static RuntimeApiException invalidEventRequest() {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_EVENT_REQUEST);
    }
}
