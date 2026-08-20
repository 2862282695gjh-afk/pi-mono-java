/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.Locale;

import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 Servlet 请求中读取认证和语言上下文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeRequestContext {
    public static final String AUTH_ATTRIBUTE = RuntimeRequestContext.class.getName() + ".auth";

    private RuntimeRequestContext() {}

    public static CallerAuthContext auth(HttpServletRequest request) {
        Object value = request.getAttribute(AUTH_ATTRIBUTE);
        if (value instanceof CallerAuthContext context) {
            return context;
        }
        throw new IllegalStateException("authentication context is missing");
    }

    public static boolean chinese(HttpServletRequest request) {
        return Locale.SIMPLIFIED_CHINESE.equals(locale(request));
    }

    public static Locale locale(HttpServletRequest request) {
        String language = request.getHeader("Accept-Language");
        if (language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("zh-cn")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return Locale.US;
    }

    public static String language(HttpServletRequest request) {
        return chinese(request) ? "zh-CN" : "en-US";
    }
}
