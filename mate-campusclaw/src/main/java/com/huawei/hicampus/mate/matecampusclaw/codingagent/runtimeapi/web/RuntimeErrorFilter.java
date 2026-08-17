/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ErrorResponseVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

/**
 * 把 Runtime 业务异常映射为不含 result 的公司错误响应。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeErrorFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {
    private static final Logger log = LoggerFactory.getLogger(RuntimeErrorFilter.class);

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        try {
            return next.handle(request).onErrorResume(error -> respond(request, error));
        } catch (Throwable error) {
            return respond(request, error);
        }
    }

    private Mono<ServerResponse> respond(ServerRequest request, Throwable error) {
        RuntimeApiException apiError = asApiError(error);
        if (apiError.status().is5xxServerError()) {
            log.error("Runtime API request failed: {} {}", request.method(), request.path(), error);
        }
        boolean chinese = languageIsChinese(request);
        var body = new ErrorResponseVO(apiError.errorCode().name(), apiError.localizedMessage(chinese));
        ServerResponse.BodyBuilder response = ServerResponse.status(apiError.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Language", chinese ? "zh-CN" : "en-US");
        if (apiError.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
            response.header("Retry-After", "3");
        }
        return response.bodyValue(body);
    }

    private static RuntimeApiException asApiError(Throwable error) {
        if (error instanceof RuntimeApiException apiError) {
            return apiError;
        }
        return new RuntimeApiException(HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.INTERNAL_ERROR, error);
    }

    private static boolean languageIsChinese(ServerRequest request) {
        Object value = request.attributes().get(RuntimeRequestContext.CHINESE_ATTRIBUTE);
        if (value instanceof Boolean chinese) {
            return chinese;
        }
        String language = request.headers().firstHeader("Accept-Language");
        return language != null
                && language.trim().toLowerCase(java.util.Locale.ROOT).startsWith("zh-cn");
    }
}
