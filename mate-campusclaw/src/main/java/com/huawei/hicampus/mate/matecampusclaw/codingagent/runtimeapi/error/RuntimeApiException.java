/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error;

import org.springframework.http.HttpStatus;

/**
 * 携带稳定 HTTP 状态和业务错误码的 Runtime API 异常。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class RuntimeApiException extends RuntimeException {
    private final HttpStatus status;

    private final RuntimeErrorCode errorCode;

    public RuntimeApiException(HttpStatus status, RuntimeErrorCode errorCode) {
        this(status, errorCode, null);
    }

    public RuntimeApiException(HttpStatus status, RuntimeErrorCode errorCode, Throwable cause) {
        super(errorCode.name(), cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public RuntimeErrorCode errorCode() {
        return errorCode;
    }
}
