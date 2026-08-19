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
     * @param bearerToken the raw JWT (without "Bearer " prefix)
     * @return JWT-mode credentials
     */
    public static MateCredentials jwt(String xHwId, String bearerToken) {
        return new MateCredentials(xHwId, null, "Bearer " + bearerToken);
    }
}
