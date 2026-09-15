/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.plan;

/**
 * ExitPlanMode 等待 Runtime 权威审核决定的窄端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface PlanReviewPort {
    PlanReviewResultDTO await(String toolCallId, String plan) throws Exception;
}
