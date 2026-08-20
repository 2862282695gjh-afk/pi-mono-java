/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime 活动执行的容量和时限配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Validated
@ConfigurationProperties(prefix = "campusclaw.runtime.execution")
public class RuntimeExecutionProperties {
    @Min(1)
    private int maxActive = 100;

    @NotNull
    private Duration maxDuration = Duration.ofMinutes(30);

    @Min(1)
    private int maxControlMessages = 32;

    @Min(1)
    private long maxControlBytes = 1024L * 1024L;

    @AssertTrue(message = "maxDuration must be positive")
    public boolean isDurationConfigurationValid() {
        return maxDuration != null && !maxDuration.isZero() && !maxDuration.isNegative();
    }
}
