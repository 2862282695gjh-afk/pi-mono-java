/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

import java.util.Objects;

/**
 * 两套认证方式归一化后的调用方身份上下文。
 *
 * @param callerId 公司调用方标识
 * @param credentialMode 已通过校验的凭据模式
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record CallerAuthContext(String callerId, CredentialMode credentialMode) {
    public CallerAuthContext {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(credentialMode, "credentialMode");
    }
}
