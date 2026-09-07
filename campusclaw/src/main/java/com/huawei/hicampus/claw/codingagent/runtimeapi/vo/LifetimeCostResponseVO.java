/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import java.math.BigDecimal;

import lombok.Getter;

/**
 * Session 生命周期 USD 费用分项的只读响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class LifetimeCostResponseVO {
    private final BigDecimal input;

    private final BigDecimal output;

    private final BigDecimal cacheRead;

    private final BigDecimal cacheWrite;

    private final BigDecimal total;

    public LifetimeCostResponseVO(
            BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite, BigDecimal total) {
        this.input = input;
        this.output = output;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.total = total;
    }
}
