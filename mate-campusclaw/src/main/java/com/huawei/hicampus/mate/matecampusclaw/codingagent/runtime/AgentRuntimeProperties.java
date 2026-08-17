/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * CampusMate runtime API and local managed-agent cache settings.
 *
 * @param baseUrl        CampusMate service base URL
 * @param agentsRoot     local root containing {@code agent/{agentId}}
 * @param connectTimeout HTTP connection timeout
 * @param requestTimeout HTTP request timeout
 * @param successCode     CampusMate business success code
 * @param maxResponseBytes maximum accepted CampusMate response size in bytes
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@ConfigurationProperties(prefix = "campusmate.runtime")
public record AgentRuntimeProperties(
        URI baseUrl,
        Path agentsRoot,
        Duration connectTimeout,
        Duration requestTimeout,
        String successCode,
        int maxResponseBytes) {

    private static final Path DEFAULT_AGENTS_ROOT = Path.of("agent");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30L);
    private static final String DEFAULT_SUCCESS_CODE = "0";
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    public AgentRuntimeProperties(URI baseUrl, Path agentsRoot, Duration connectTimeout, Duration requestTimeout) {
        this(baseUrl, agentsRoot, connectTimeout, requestTimeout, DEFAULT_SUCCESS_CODE, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public AgentRuntimeProperties(
            URI baseUrl, Path agentsRoot, Duration connectTimeout, Duration requestTimeout, String successCode) {
        this(baseUrl, agentsRoot, connectTimeout, requestTimeout, successCode, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Applies local-cache and timeout defaults when configuration values are omitted.
     */
    @ConstructorBinding
    public AgentRuntimeProperties {
        agentsRoot = agentsRoot == null ? DEFAULT_AGENTS_ROOT : agentsRoot;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        successCode = successCode == null || successCode.isBlank() ? DEFAULT_SUCCESS_CODE : successCode;
        maxResponseBytes = maxResponseBytes <= 0 ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
    }
}
