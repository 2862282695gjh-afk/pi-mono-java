/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

/**
 * 隔离公司 JWT 与 APPKEY 制品的凭据校验端口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface RuntimeCredentialVerifier {
    /**
     * 校验 JWT 凭据。
     *
     * @param callerId 调用方标识
     * @param token 不含 Bearer 前缀的令牌
     * @return 是否通过校验
     */
    boolean verifyJwt(String callerId, String token);

    /**
     * 校验 APPKEY 凭据。
     *
     * @param callerId 调用方标识
     * @param appKey APPKEY
     * @return 是否通过校验
     */
    boolean verifyAppKey(String callerId, String appKey);
}
