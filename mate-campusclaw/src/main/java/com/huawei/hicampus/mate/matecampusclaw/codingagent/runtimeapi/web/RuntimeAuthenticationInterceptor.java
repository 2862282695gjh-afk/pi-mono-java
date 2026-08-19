/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 为 Runtime HTTP V1 请求安装凭据上下文。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeAuthenticationInterceptor implements HandlerInterceptor {
    private final RuntimeRequestAuthenticator authenticator;

    public RuntimeAuthenticationInterceptor(RuntimeRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(RuntimeRequestContext.AUTH_ATTRIBUTE, authenticator.authenticate(request));
        return true;
    }
}
