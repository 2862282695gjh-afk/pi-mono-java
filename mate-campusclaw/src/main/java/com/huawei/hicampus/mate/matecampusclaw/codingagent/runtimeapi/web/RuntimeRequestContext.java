/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import java.util.List;
import java.util.Locale;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CallerAuthContext;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 Servlet 请求中读取认证和语言上下文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeRequestContext {
    public static final String AUTH_ATTRIBUTE = RuntimeRequestContext.class.getName() + ".auth";

    private static final List<Locale> SUPPORTED_LOCALES = List.of(Locale.US, Locale.SIMPLIFIED_CHINESE);

    private RuntimeRequestContext() {}

    public static CallerAuthContext auth(HttpServletRequest request) {
        Object value = request.getAttribute(AUTH_ATTRIBUTE);
        if (value instanceof CallerAuthContext context) {
            return context;
        }
        throw new IllegalStateException("authentication context is missing");
    }

    public static Locale locale(HttpServletRequest request) {
        String acceptLanguage = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.US;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            return Locale.filter(ranges, SUPPORTED_LOCALES).stream().findFirst().orElse(Locale.US);
        } catch (IllegalArgumentException error) {
            return Locale.US;
        }
    }

    public static String language(HttpServletRequest request) {
        return locale(request).toLanguageTag();
    }
}
