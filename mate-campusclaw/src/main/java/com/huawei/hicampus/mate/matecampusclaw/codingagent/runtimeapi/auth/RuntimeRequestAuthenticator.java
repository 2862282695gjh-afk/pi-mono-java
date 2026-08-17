/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * 按互斥规则解析并校验 JWT 或 APPKEY 请求头。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeRequestAuthenticator {
    public static final String HEADER_HW_ID = "X-HW-ID";

    public static final String HEADER_HW_APPKEY = "X-HW-APPKEY";

    private final RuntimeCredentialVerifier verifier;

    public RuntimeRequestAuthenticator(RuntimeCredentialVerifier verifier) {
        this.verifier = verifier;
    }

    public CallerAuthContext authenticate(ServerRequest request) {
        String callerId = trim(request.headers().firstHeader(HEADER_HW_ID));
        String authorization = trim(request.headers().firstHeader(HttpHeaders.AUTHORIZATION));
        String appKey = trim(request.headers().firstHeader(HEADER_HW_APPKEY));
        if (authorization != null && appKey != null) {
            throw error(RuntimeErrorCode.AUTH_CREDENTIAL_CONFLICT);
        }
        if (callerId == null) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        if (authorization != null) {
            return authenticateJwt(callerId, authorization);
        }
        if (appKey != null && verifier.verifyAppKey(callerId, appKey)) {
            return new CallerAuthContext(callerId, CredentialMode.APPKEY);
        }
        throw error(RuntimeErrorCode.UNAUTHENTICATED);
    }

    private CallerAuthContext authenticateJwt(String callerId, String authorization) {
        if (!authorization.startsWith("Bearer ") || authorization.length() == 7) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        String token = authorization.substring(7).trim();
        if (!verifier.verifyJwt(callerId, token)) {
            throw error(RuntimeErrorCode.UNAUTHENTICATED);
        }
        return new CallerAuthContext(callerId, CredentialMode.JWT);
    }

    private static RuntimeApiException error(RuntimeErrorCode code) {
        return new RuntimeApiException(HttpStatus.UNAUTHORIZED, code);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
