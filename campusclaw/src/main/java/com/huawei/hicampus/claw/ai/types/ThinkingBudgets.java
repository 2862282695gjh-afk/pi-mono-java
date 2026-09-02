/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.annotation.Nullable;

/**
 * Token budgets for each thinking level.
 *
 * @param minimal token budget for minimal thinking
 * @param low     token budget for low thinking
 * @param medium  token budget for medium thinking
 * @param high    token budget for high thinking
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record ThinkingBudgets(
        @JsonProperty("minimal") @Nullable Integer minimal,
        @JsonProperty("low") @Nullable Integer low,
        @JsonProperty("medium") @Nullable Integer medium,
        @JsonProperty("high") @Nullable Integer high) {}
