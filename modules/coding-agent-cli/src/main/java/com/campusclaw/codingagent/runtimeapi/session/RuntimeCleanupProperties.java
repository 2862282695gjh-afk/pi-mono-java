/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Runtime Session 异步物理清理任务的调度配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
@ConfigurationProperties(prefix = "campusclaw.runtime.cleanup")
public class RuntimeCleanupProperties {
    private int batchSize = 10;

    private Duration retryDelay = Duration.ofSeconds(30);

    private Duration runningTimeout = Duration.ofMinutes(5);
}
