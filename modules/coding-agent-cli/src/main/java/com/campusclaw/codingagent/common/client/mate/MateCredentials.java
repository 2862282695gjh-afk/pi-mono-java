/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client.mate;

/**
 * Credentials for invoking a Mate tool, handed down by the agent. Two modes:
 * AppKey (X-HW-ID + X-HW-APPKEY) or JWT (X-HW-ID + Authorization Bearer).
 * Only {@code callTool} carries them; {@code listTools} runs credential-free.
 *
 * @param xHwId X-HW-ID header (always required)
 * @param xHwAppKey X-HW-APPKEY header (AppKey mode; null for JWT)
 * @param authorization Authorization header (JWT mode; null for AppKey)
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateCredentials(String xHwId, String xHwAppKey, String authorization) {

    /**
     * Creates AppKey-mode credentials.
     *
     * @param xHwId the X-HW-ID header value
     * @param xHwAppKey the X-HW-APPKEY header value
     * @return AppKey-mode credentials
     */
    public static MateCredentials appKey(String xHwId, String xHwAppKey) {
        return new MateCredentials(xHwId, xHwAppKey, null);
    }

    /**
     * Creates JWT-mode credentials.
     *
     * @param xHwId the X-HW-ID header value
     * @param bearerToken the raw JWT (without "Bearer " prefix); must be
     *        non-null and non-blank — an empty token would otherwise be
     *        wrapped into {@code "Bearer "}/-{@code null} and slip past the
     *        completeness check as a seemingly valid header
     * @return JWT-mode credentials
     * @throws IllegalArgumentException when the bearer token is null or blank
     */
    public static MateCredentials jwt(String xHwId, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearer token must not be null or blank");
        }
        return new MateCredentials(xHwId, null, "Bearer " + bearerToken);
    }

    /**
     * 判断凭据是否构成一次完整认证：{@code X-HW-ID} 非空，且 AppKey 与
     * JWT 两种模式恰好存在一种（对应 Header 非空）。非空但残缺的凭据
     * （如全 null、空 appKey、空 Authorization、双模式并存）均视为无效，
     * 调用端应在发请求前拒绝，避免发出半认证请求。
     *
     * @return 凭据完整时为 {@code true}
     */
    public boolean isComplete() {
        boolean hasAppKey = xHwAppKey != null && !xHwAppKey.isBlank();
        boolean hasAuthorization = authorization != null && !authorization.isBlank();
        boolean idPresent = xHwId != null && !xHwId.isBlank();
        return idPresent && (hasAppKey ^ hasAuthorization);
    }
}
