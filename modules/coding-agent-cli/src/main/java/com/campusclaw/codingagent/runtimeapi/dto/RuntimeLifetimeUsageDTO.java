/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * Session 生命周期 Token 与 USD 费用分项的数据库传输对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class RuntimeLifetimeUsageDTO {
    private long input;

    private long output;

    private long cacheRead;

    private long cacheWrite;

    private long totalTokens;

    private BigDecimal costInput = BigDecimal.ZERO;

    private BigDecimal costOutput = BigDecimal.ZERO;

    private BigDecimal costCacheRead = BigDecimal.ZERO;

    private BigDecimal costCacheWrite = BigDecimal.ZERO;

    private BigDecimal costTotal = BigDecimal.ZERO;
}
