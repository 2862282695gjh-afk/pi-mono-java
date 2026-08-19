/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 按互斥规则解析 JWT 或 APPKEY 请求头。
 *
 * <p>CampusClaw 位于受控内部网络中，仅校验凭据 Header 形状；JWT 与 APPKEY 的
 * 真实性由上游 mate-service 负责，不在此处重复实现公司算法。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeRequestAuthenticator {
    public static final String HEADER_HW_ID = "X-HW-ID";

    public static final String HEADER_HW_APPKEY = "X-HW-APPKEY";

    public CallerAuthContext authenticate(HttpServletRequest request) {
        String credentialId = singleHeader(request, HEADER_HW_ID);
        String authorization = singleHeader(request, HttpHeaders.AUTHORIZATION);
        String appKey = singleHeader(request, HEADER_HW_APPKEY);
        if (authorization != null && appKey != null) {
            throw error(RuntimeErrorCode.AUTH_CREDENTIAL_CONFLICT);
        }
        if (credentialId == null) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        if (authorization != null) {
            return authenticateJwt(credentialId, authorization);
        }
        if (appKey != null) {
            return new CallerAuthContext(credentialId, CredentialMode.APPKEY);
        }
        throw error(RuntimeErrorCode.UNAUTHENTICATED);
    }

    private CallerAuthContext authenticateJwt(String credentialId, String authorization) {
        if (authorization.length() <= 7 || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        return new CallerAuthContext(credentialId, CredentialMode.JWT);
    }

    private static RuntimeApiException error(RuntimeErrorCode code) {
        return new RuntimeApiException(code);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        java.util.List<String> values = java.util.Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        if (values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
