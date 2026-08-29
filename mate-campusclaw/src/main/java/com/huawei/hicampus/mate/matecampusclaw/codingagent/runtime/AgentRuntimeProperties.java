/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * CampusMate 运行时接口和本地托管 Agent 缓存配置。
 *
 * @param agentsRoot 包含 {@code agent/{agentId}} 的本地根目录
 * @param connectTimeout HTTP 连接超时
 * @param requestTimeout HTTP 请求超时
 * @param maxResponseBytes CampusMate 响应最大允许字节数
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@ConfigurationProperties(prefix = "campusmate.runtime")
public record AgentRuntimeProperties(
        Path agentsRoot, Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {

    private static final Path DEFAULT_AGENTS_ROOT = Path.of("agent");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30L);
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    public AgentRuntimeProperties(Path agentsRoot, Duration connectTimeout, Duration requestTimeout) {
        this(agentsRoot, connectTimeout, requestTimeout, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * 配置项缺失时应用本地缓存与超时默认值。
     */
    @ConstructorBinding
    public AgentRuntimeProperties {
        agentsRoot = agentsRoot == null ? DEFAULT_AGENTS_ROOT : agentsRoot;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
    }
}
