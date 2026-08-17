/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;

import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * 从函数式 WebFlux 请求中读取认证和语言上下文。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public final class RuntimeRequestContext {
    public static final String AUTH_ATTRIBUTE = RuntimeRequestContext.class.getName() + ".auth";

    public static final String CHINESE_ATTRIBUTE = RuntimeRequestContext.class.getName() + ".chinese";

    private RuntimeRequestContext() {}

    public static CallerAuthContext auth(ServerRequest request) {
        return request.attribute(AUTH_ATTRIBUTE)
                .filter(CallerAuthContext.class::isInstance)
                .map(CallerAuthContext.class::cast)
                .orElseThrow(() -> new IllegalStateException("authentication context is missing"));
    }

    public static boolean chinese(ServerRequest request) {
        return request.attribute(CHINESE_ATTRIBUTE)
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(false);
    }

    public static String language(ServerRequest request) {
        return chinese(request) ? "zh-CN" : "en-US";
    }
}
