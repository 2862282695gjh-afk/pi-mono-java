/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

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
}
