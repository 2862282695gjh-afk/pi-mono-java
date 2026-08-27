/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider.mate;

/**
 * 表示 Mate Model Manager 的 HTTP、协议或本地映射错误。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateModelInvocationException extends RuntimeException {
<<<<<<< HEAD
    private final String errorCode;

    public MateModelInvocationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MateModelInvocationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
=======
    private final MateInvocationErrorCode errorCode;

    public MateModelInvocationException(MateInvocationErrorCode errorCode) {
        super(errorCode.name(), null, false, false);
        this.errorCode = errorCode;
    }

    public MateInvocationErrorCode errorCode() {
>>>>>>> upstream/main
        return errorCode;
    }
}
