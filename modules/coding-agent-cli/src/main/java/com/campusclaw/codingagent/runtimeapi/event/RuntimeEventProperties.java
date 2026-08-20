/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime 事件分页游标配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Validated
@ConfigurationProperties(prefix = "campusclaw.runtime.events")
public class RuntimeEventProperties {
    private String cursorSecret;

    @NotNull
    private Duration cursorTtl = Duration.ofHours(24);

    @Min(1)
    private int streamBufferEvents = 256;

    @Min(1)
    private long streamBufferBytes = 1024L * 1024L;

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    @AssertTrue(message = "cursorTtl and heartbeatInterval must be positive")
    public boolean isDurationConfigurationValid() {
        return positive(cursorTtl) && positive(heartbeatInterval);
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
