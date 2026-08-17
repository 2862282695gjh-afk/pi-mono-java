/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * 根据 Session 标识和资源版本生成不泄露内部版本号的强 ETag。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class SessionEtagFactory {
    public String create(String sessionId, long resourceVersion) {
        String material = sessionId + ':' + resourceVersion;
        byte[] digest = digest(material.getBytes(StandardCharsets.UTF_8));
        String token =
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 12);
        return "\"snp-" + token + "\"";
    }

    private static byte[] digest(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
