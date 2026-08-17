/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 独立开发模式使用的显式静态凭据校验器。
 *
 * <p>未配置凭据时始终拒绝，不提供匿名或万能密钥。公司环境可直接提供
 * {@link RuntimeCredentialVerifier} Bean 替换该实现。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class StandaloneCredentialVerifier implements RuntimeCredentialVerifier {
    private final RuntimeAuthProperties properties;

    public StandaloneCredentialVerifier(RuntimeAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean verifyJwt(String callerId, String token) {
        return callerAllowed(callerId) && secretMatches(properties.getJwtToken(), token);
    }

    @Override
    public boolean verifyAppKey(String callerId, String appKey) {
        return callerAllowed(callerId) && secretMatches(properties.getAppKey(), appKey);
    }

    private boolean callerAllowed(String callerId) {
        var allowed = properties.getAllowedCallers();
        return allowed == null || allowed.isEmpty() || allowed.contains(callerId);
    }

    private static boolean secretMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
