/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for the Mate tool client and AgentTools.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * mate:
 *   tool:
 *     enabled: true
 * </pre>
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@ConfigurationProperties(prefix = "mate.tool")
public class MateToolProperties {

    /**
     * Master enable switch; when false neither Mate AgentTool is registered.
     */
    private boolean enabled = true;
}
