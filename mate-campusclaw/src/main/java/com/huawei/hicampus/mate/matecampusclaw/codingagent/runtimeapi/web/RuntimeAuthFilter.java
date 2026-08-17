/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

/**
 * 为 Runtime 路由安装认证上下文和受支持语言的过滤器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeAuthFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {
    private final RuntimeRequestAuthenticator authenticator;

    public RuntimeAuthFilter(RuntimeRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        boolean chinese = acceptsChinese(request);
        var auth = authenticator.authenticate(request);
        ServerRequest enriched = ServerRequest.from(request)
                .attribute(RuntimeRequestContext.AUTH_ATTRIBUTE, auth)
                .attribute(RuntimeRequestContext.CHINESE_ATTRIBUTE, chinese)
                .build();
        return next.handle(enriched);
    }

    private static boolean acceptsChinese(ServerRequest request) {
        String language = request.headers().firstHeader("Accept-Language");
        return language != null
                && language.trim().toLowerCase(java.util.Locale.ROOT).startsWith("zh-cn");
    }
}
