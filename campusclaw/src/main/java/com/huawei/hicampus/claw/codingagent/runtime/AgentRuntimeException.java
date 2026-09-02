/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtime;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;

/**
 * Agent 运行时获取、准备或激活失败时抛出。
 * 携带稳定错误码供公开边界（HTTP/SSE、Child 工具结果、Cron 运行记录）映射，
 * 英文消息仅作为日志与诊断，不直接对外展示。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class AgentRuntimeException extends IllegalStateException implements StableErrorCode {

    private final AgentRuntimeErrorCode errorCode;

    public AgentRuntimeException(String message) {
        this(AgentRuntimeErrorCode.RUNTIME_PREPARE_FAILED, message, null);
    }

    public AgentRuntimeException(String message, Throwable cause) {
        this(AgentRuntimeErrorCode.RUNTIME_PREPARE_FAILED, message, cause);
    }

    public AgentRuntimeException(AgentRuntimeErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public AgentRuntimeException(AgentRuntimeErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? AgentRuntimeErrorCode.RUNTIME_PREPARE_FAILED : errorCode;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 稳定错误码
     */
    public AgentRuntimeErrorCode errorCode() {
        return errorCode;
    }

    @Override
    public String stableErrorCode() {
        return errorCode.name();
    }
}
