/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

/**
 * 工具实参未通过结构或语义校验时返回稳定错误码。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class ToolArgumentsInvalidException extends IllegalArgumentException implements StableErrorCode {
    public ToolArgumentsInvalidException(String message) {
        super(message);
    }

    @Override
    public String stableErrorCode() {
        return ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE;
    }
}
