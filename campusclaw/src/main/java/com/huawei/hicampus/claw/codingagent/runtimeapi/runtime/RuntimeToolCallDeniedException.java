/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

/**
 * 把可信权限拒绝映射为稳定工具错误码，避免公开内部诊断文本。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeToolCallDeniedException extends RuntimeException implements StableErrorCode {
    public RuntimeToolCallDeniedException() {
        super("runtime tool permission denied the call");
    }

    @Override
    public String stableErrorCode() {
        return ClawConstants.RuntimeApi.TOOL_CALL_DENIED_ERROR_CODE;
    }
}
