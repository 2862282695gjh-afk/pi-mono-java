/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * 定义 Mate 模型目录接口冻结前所需的本地能力默认值。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@ConfigurationProperties(prefix = "campusmate.model")
public class MateModelManagerProperties {
    private int contextWindow = 128_000;

    private int maxOutputTokens = 8192;

    private boolean reasoning = true;
}
