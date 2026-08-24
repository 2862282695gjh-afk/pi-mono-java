/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo;

import lombok.Getter;

/**
 * Runtime Session 的 USD 费用明细响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class CostResponseVO {
    private final double input;

    private final double output;

    private final double cacheRead;

    private final double cacheWrite;

    private final double total;

    public CostResponseVO(double input, double output, double cacheRead, double cacheWrite, double total) {
        this.input = input;
        this.output = output;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.total = total;
    }
}
