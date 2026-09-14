/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.plan;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;

/**
 * 计划审核无法安全继续时返回稳定工具错误码。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class PlanReviewException extends RuntimeException implements StableErrorCode {
    private final String errorCode;

    public PlanReviewException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    @Override
    public String stableErrorCode() {
        return errorCode;
    }
}
