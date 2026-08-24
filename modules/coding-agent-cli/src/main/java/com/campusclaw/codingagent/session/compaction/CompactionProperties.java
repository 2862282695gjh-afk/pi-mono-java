/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 公共 Agent Session 的上下文压缩参数。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@ConfigurationProperties(prefix = "campusclaw.runtime.compaction")
public class CompactionProperties {
    private boolean enabled = true;

    private int reserveTokens = 16_384;

    private int keepRecentTokens = 20_000;

    private boolean summaryRetryEnabled = true;

    private int summaryMaxRetries = 3;

    private long summaryRetryBaseDelayMs = 2_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getReserveTokens() {
        return reserveTokens;
    }

    public void setReserveTokens(int reserveTokens) {
        if (reserveTokens <= 0) {
            throw new IllegalArgumentException("reserveTokens must be positive");
        }
        this.reserveTokens = reserveTokens;
    }

    public int getKeepRecentTokens() {
        return keepRecentTokens;
    }

    public void setKeepRecentTokens(int keepRecentTokens) {
        if (keepRecentTokens <= 0) {
            throw new IllegalArgumentException("keepRecentTokens must be positive");
        }
        this.keepRecentTokens = keepRecentTokens;
    }

    public boolean isSummaryRetryEnabled() {
        return summaryRetryEnabled;
    }

    public void setSummaryRetryEnabled(boolean summaryRetryEnabled) {
        this.summaryRetryEnabled = summaryRetryEnabled;
    }

    public int getSummaryMaxRetries() {
        return summaryMaxRetries;
    }

    public void setSummaryMaxRetries(int summaryMaxRetries) {
        if (summaryMaxRetries < 0) {
            throw new IllegalArgumentException("summaryMaxRetries must not be negative");
        }
        this.summaryMaxRetries = summaryMaxRetries;
    }

    public long getSummaryRetryBaseDelayMs() {
        return summaryRetryBaseDelayMs;
    }

    public void setSummaryRetryBaseDelayMs(long summaryRetryBaseDelayMs) {
        if (summaryRetryBaseDelayMs <= 0) {
            throw new IllegalArgumentException("summaryRetryBaseDelayMs must be positive");
        }
        this.summaryRetryBaseDelayMs = summaryRetryBaseDelayMs;
    }
}
