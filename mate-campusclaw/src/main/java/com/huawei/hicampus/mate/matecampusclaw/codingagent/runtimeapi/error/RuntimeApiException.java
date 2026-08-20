/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error;

import org.springframework.http.HttpStatus;

/**
 * 携带稳定 HTTP 状态和业务错误码的 Runtime API 异常。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeApiException extends RuntimeException {
    private final RuntimeErrorCode errorCode;

    public RuntimeApiException(RuntimeErrorCode errorCode) {
        this(errorCode, null);
    }

    public RuntimeApiException(RuntimeErrorCode errorCode, Throwable cause) {
        super(errorCode.name(), cause);
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return errorCode.status();
    }

    public RuntimeErrorCode errorCode() {
        return errorCode;
    }
}
