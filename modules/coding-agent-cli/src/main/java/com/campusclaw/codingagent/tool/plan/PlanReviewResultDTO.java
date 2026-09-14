/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.plan;

/**
 * ExitPlanMode 返回给模型的可信审核决定。
 *
 * @param approved 是否批准
 * @param feedback 修订意见
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record PlanReviewResultDTO(boolean approved, String feedback) {}
