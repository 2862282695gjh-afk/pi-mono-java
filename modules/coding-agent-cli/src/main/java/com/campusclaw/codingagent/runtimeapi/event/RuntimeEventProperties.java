/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Runtime 事件分页游标配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
@ConfigurationProperties(prefix = "campusclaw.runtime.events")
public class RuntimeEventProperties {
    private String cursorSecret;

    private Duration cursorTtl = Duration.ofHours(24);
}
