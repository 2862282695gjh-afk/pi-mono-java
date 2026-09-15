/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.question;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;

/**
 * 用户问题无法安全等待或续跑时返回稳定工具错误码。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class UserQuestionException extends RuntimeException implements StableErrorCode {
    private final String errorCode;

    public UserQuestionException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    @Override
    public String stableErrorCode() {
        return errorCode;
    }
}
