/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

/**
 * Header information carried on every request to the Mate inner gateway.
 *
 * <p>Sensitive fields (tokens, credentials, cookies) are excluded from
 * {@link #toString()} so they never leak into logs. Internal Mate gateway
 * endpoints do not require credential headers; a default-built instance
 * ({@code RequestHeaderInfo.builder().build()}) is sufficient.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Builder
public class RequestHeaderInfo {

    @ToString.Exclude
    private String accessToken;

    private String clientIp;

    private String locale;

    private String xForward;

    private String appId;

    @ToString.Exclude
    private String appKey;

    @ToString.Exclude
    private String cookie;

    @ToString.Exclude
    private String csrfToken;

    @ToString.Exclude
    private String authorization;

    @ToString.Exclude
    private String xAuthToken;

    @ToString.Exclude
    private String roaRand;

    private String xAgentId;

    private String agentTenantId;

    private String agentUserId;

    private String a2aVersion;

    private String xPlaneType;

    @ToString.Exclude
    private Map<String, String> customHeaders;

    /**
     * Maps the header-info fields onto HTTP header names as expected by the
     * Mate inner gateway; null fields are omitted. {@code customHeaders}
     * entries are merged in as-is.
     *
     * @return header name to value map (values may be null)
     */
    public Map<String, String> toHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Access-Token", accessToken);
        headers.put("X-Client-IP", clientIp);
        headers.put("X-Locale", locale);
        headers.put("X-Forward", xForward);
        headers.put("X-App-Id", appId);
        headers.put("X-App-Key", appKey);
        headers.put("Cookie", cookie);
        headers.put("X-Csrf-Token", csrfToken);
        headers.put("Authorization", authorization);
        headers.put("X-Auth-Token", xAuthToken);
        headers.put("X-Roa-Rand", roaRand);
        headers.put("X-Agent-Id", xAgentId);
        headers.put("X-Agent-Tenant-Id", agentTenantId);
        headers.put("X-Agent-User-Id", agentUserId);
        headers.put("X-A2a-Version", a2aVersion);
        headers.put("X-Plane-Type", xPlaneType);
        if (customHeaders != null) {
            customHeaders.forEach(headers::putIfAbsent);
        }
        return headers;
    }
}
