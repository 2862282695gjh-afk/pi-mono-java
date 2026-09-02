/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.common.client.mate;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;

/**
 * Mate 网关响应无法解析为所需结果时抛出。携带稳定错误码供公开边界映射；
 * 英文消息仅作为日志与诊断。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolResponseException extends IllegalStateException implements StableErrorCode {

    /** 稳定错误码：Mate 网关响应体为空或 result 缺失/形状不符。 */
    public static final String ERROR_CODE = "MATE_TOOL_RESPONSE_INVALID";

    public MateToolResponseException(String detail) {
        super("Mate tool gateway response is invalid: " + detail);
    }

    public MateToolResponseException(String detail, Throwable cause) {
        super("Mate tool gateway response is invalid: " + detail, cause);
    }

    @Override
    public String stableErrorCode() {
        return ERROR_CODE;
    }
}
