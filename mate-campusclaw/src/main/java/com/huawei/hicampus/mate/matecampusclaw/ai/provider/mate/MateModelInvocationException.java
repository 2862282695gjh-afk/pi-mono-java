/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.provider.mate;

/**
 * 表示 Mate Model Manager 的 HTTP、协议或本地映射错误。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateModelInvocationException extends RuntimeException {
    private final MateInvocationErrorCode errorCode;

    public MateModelInvocationException(MateInvocationErrorCode errorCode) {
        super(errorCode.name(), null, false, false);
        this.errorCode = errorCode;
    }

    public MateInvocationErrorCode errorCode() {
        return errorCode;
    }
}
