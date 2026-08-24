/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo;

import lombok.Getter;

/**
 * Runtime Session 的 Token 与费用用量响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class UsageResponseVO {
    private final int input;

    private final int output;

    private final int cacheRead;

    private final int cacheWrite;

    private final int totalTokens;

    private final CostResponseVO cost;

    public UsageResponseVO(int input, int output, int cacheRead, int cacheWrite, int totalTokens, CostResponseVO cost) {
        this.input = input;
        this.output = output;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.totalTokens = totalTokens;
        this.cost = cost;
    }
}
