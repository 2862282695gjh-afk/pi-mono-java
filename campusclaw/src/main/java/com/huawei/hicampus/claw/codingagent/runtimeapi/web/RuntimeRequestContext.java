/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import java.util.List;
import java.util.Locale;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentialHeaders;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 Servlet 请求中读取语言与本次 Mate 工具执行凭据上下文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeRequestContext {
    private static final List<Locale> SUPPORTED_LOCALES = List.of(Locale.US, Locale.SIMPLIFIED_CHINESE);

    private RuntimeRequestContext() {}

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

    public static MateCredentials mateCredentials(HttpServletRequest request) {
        return new MateCredentials(
                request.getHeader(MateCredentialHeaders.X_HW_ID),
                request.getHeader(MateCredentialHeaders.X_HW_APPKEY),
                request.getHeader(MateCredentialHeaders.AUTHORIZATION),
                request.getHeader(MateCredentialHeaders.ACCESS_TOKEN));
    }
}
