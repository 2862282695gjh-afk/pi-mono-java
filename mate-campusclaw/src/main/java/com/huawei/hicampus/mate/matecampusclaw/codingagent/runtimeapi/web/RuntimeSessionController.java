/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import java.net.URI;
import java.util.regex.Pattern;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.ResultBeanFactory;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

/**
 * Runtime Session 前三个 HTTP 接口的函数式 Controller。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeSessionController {
    private static final Pattern AGENT_ID = Pattern.compile(RuntimeApiConstants.AGENT_ID_PATTERN);

    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private final RuntimeSessionService service;

    public RuntimeSessionController(RuntimeSessionService service) {
        this.service = service;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        String agentId =
                requireIdentifier(request.pathVariable("agent_id"), AGENT_ID, RuntimeErrorCode.INVALID_AGENT_ID);
        var view = service.create(agentId, RuntimeRequestContext.auth(request));
        URI location = URI.create(
                RuntimeApiConstants.BASE_PATH + "/sessions/" + view.resource().getSessionId());
        return ServerResponse.created(location)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Language", RuntimeRequestContext.language(request))
                .bodyValue(ResultBeanFactory.getFactory().normal(view.resource()));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String sessionId =
                requireIdentifier(request.pathVariable("session_id"), SESSION_ID, RuntimeErrorCode.INVALID_SESSION_ID);
        var view = service.get(sessionId, RuntimeRequestContext.auth(request));
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Language", RuntimeRequestContext.language(request))
                .header("Cache-Control", "no-store")
                .eTag(view.etag())
                .bodyValue(ResultBeanFactory.getFactory().normal(view.resource()));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String sessionId =
                requireIdentifier(request.pathVariable("session_id"), SESSION_ID, RuntimeErrorCode.INVALID_SESSION_ID);
        service.delete(sessionId, RuntimeRequestContext.auth(request));
        return ServerResponse.noContent().build();
    }

    private static String requireIdentifier(String value, Pattern pattern, RuntimeErrorCode errorCode) {
        if (!pattern.matcher(value).matches()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, errorCode);
        }
        return value;
    }
}
