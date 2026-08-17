/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * 独立开发模式的 Runtime HTTP 静态凭据配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
@ConfigurationProperties(prefix = "campusclaw.runtime.auth")
public class RuntimeAuthProperties {
    private String jwtToken;

    private String appKey;

    private Set<String> allowedCallers = Set.of();
}
