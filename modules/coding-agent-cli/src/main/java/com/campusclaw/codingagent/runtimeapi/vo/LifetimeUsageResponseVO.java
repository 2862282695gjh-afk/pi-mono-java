/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import lombok.Getter;

/**
 * Session 从创建至今原子累计的 Token 与费用只读响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class LifetimeUsageResponseVO {
    private final long input;

    private final long output;

    private final long cacheRead;

    private final long cacheWrite;

    private final long totalTokens;

    private final LifetimeCostResponseVO cost;

    public LifetimeUsageResponseVO(
            long input, long output, long cacheRead, long cacheWrite, long totalTokens, LifetimeCostResponseVO cost) {
        this.input = input;
        this.output = output;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.totalTokens = totalTokens;
        this.cost = cost;
    }
}
