/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.tool;

import com.campusclaw.agent.error.StableErrorCode;
import com.campusclaw.common.constant.ClawConstants;

/**
 * 可信工具输入 Schema 无法编译或执行校验时抛出的配置异常。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public class ToolSchemaInvalidException extends IllegalStateException implements StableErrorCode {

    public ToolSchemaInvalidException(Throwable cause) {
        super("Tool input schema is invalid", cause);
    }

    @Override
    public String stableErrorCode() {
        return ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE;
    }
}
