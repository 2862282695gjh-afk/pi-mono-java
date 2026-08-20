/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime Session 异步物理清理任务的调度配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Validated
@ConfigurationProperties(prefix = "campusclaw.runtime.cleanup")
public class RuntimeCleanupProperties {
    @Min(1)
    private int batchSize = 10;

    @NotNull
    private Duration retryDelay = Duration.ofSeconds(30);

    @NotNull
    private Duration runningTimeout = Duration.ofMinutes(5);

    @AssertTrue(message = "retryDelay and runningTimeout must be positive")
    public boolean isDurationConfigurationValid() {
        return positive(retryDelay) && positive(runningTimeout);
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
