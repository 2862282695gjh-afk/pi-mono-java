/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction;

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
}
