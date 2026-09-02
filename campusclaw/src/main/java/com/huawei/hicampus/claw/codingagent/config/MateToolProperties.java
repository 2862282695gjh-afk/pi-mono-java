/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Mate Tool 客户端与 AgentTool 装配配置。
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * campusmate:
 *   tool:
 *     enabled: true
 * </pre>
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@ConfigurationProperties(prefix = "campusmate.tool")
public class MateToolProperties {
    // 关闭时不装配任何 Mate AgentTool。
    private boolean enabled = true;
}
