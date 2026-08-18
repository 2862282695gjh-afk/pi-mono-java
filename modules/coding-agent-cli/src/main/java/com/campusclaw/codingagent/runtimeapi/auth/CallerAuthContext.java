/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

import java.util.Objects;

/**
 * 两套凭据 Header 归一化后的请求上下文。
 *
 * @param credentialId {@code X-HW-ID} 携带的凭据标识，不代表资源所有者
 * @param credentialMode 请求选择的凭据模式
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record CallerAuthContext(String credentialId, CredentialMode credentialMode) {
    public CallerAuthContext {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(credentialMode, "credentialMode");
    }
}
