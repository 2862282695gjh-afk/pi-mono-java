/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import com.campusclaw.agent.error.StableErrorCode;

/**
 * Signals that a managed Agent runtime could not be fetched, materialized, or activated.
 * 携带稳定错误码供公开边界映射；英文消息仅作为日志与诊断。
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
