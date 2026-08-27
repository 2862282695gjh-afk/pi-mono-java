/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate;

/**
 * 保存一次 Agent 执行向 Mate 工具执行接口透传的不可变凭据快照。
 *
 * <p>Runtime 不验证凭据真实性，也不把凭据持久化到 Session、消息或事件。工具发现接口
 * 不接收本快照；执行接口要求 {@code X-HW-ID}、{@code access-token} 与 AppKey/JWT 至少一种
 * 同时存在。两种身份凭据同时出现时保持原样交由 Mate 做最终授权。
 *
 * @param xHwId {@code X-HW-ID} 请求头
 * @param xHwAppKey {@code X-HW-APPKEY} 请求头
 * @param authorization {@code Authorization} 请求头
 * @param accessToken {@code access-token} 请求头
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateCredentials(String xHwId, String xHwAppKey, String authorization, String accessToken) {

    private static final MateCredentials EMPTY = new MateCredentials(null, null, null, null);

    /**
     * 返回不携带任何凭据的共享不可变实例。
     *
     * @return 空凭据
     */
    public static MateCredentials empty() {
        return EMPTY;
    }

    /**
     * 创建 AppKey 模式凭据。
     *
     * @param xHwId X-HW-ID 请求头值
     * @param xHwAppKey X-HW-APPKEY 请求头值
     * @param accessToken access-token 请求头值
     * @return AppKey 模式凭据
     */
    public static MateCredentials appKey(String xHwId, String xHwAppKey, String accessToken) {
        return new MateCredentials(xHwId, xHwAppKey, null, accessToken);
    }

    /**
     * 创建 JWT 模式凭据。
     *
     * @param xHwId X-HW-ID 请求头值
     * @param bearerToken 不含 {@code Bearer } 前缀的 JWT
     * @param accessToken access-token 请求头值
     * @return JWT 模式凭据
     * @throws IllegalArgumentException JWT 为空时抛出
     */
    public static MateCredentials jwt(String xHwId, String bearerToken, String accessToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearer token must not be null or blank");
        }
        return new MateCredentials(xHwId, null, "Bearer " + bearerToken, accessToken);
    }

    /**
     * 判断凭据是否满足 Mate 工具执行的最低透传要求。
     *
     * <p>{@code X-HW-ID} 与 {@code access-token} 必须非空，AppKey 与 JWT 至少一种非空。
     * Runtime HTTP 允许两类上游调用上下文同时出现，因此这里不做互斥校验。
     *
     * @return 凭据完整时为 {@code true}
     */
    public boolean isComplete() {
        boolean hasAppKey = xHwAppKey != null && !xHwAppKey.isBlank();
        boolean hasAuthorization = authorization != null && !authorization.isBlank();
        boolean idPresent = xHwId != null && !xHwId.isBlank();
        return idPresent && present(accessToken) && (hasAppKey || hasAuthorization);
    }

    /**
     * 返回不包含任何敏感值的诊断字符串。
     *
     * @return 脱敏后的凭据状态
     */
    @Override
    public String toString() {
        return "MateCredentials[xHwIdPresent=" + present(xHwId) + ", xHwAppKeyPresent=" + present(xHwAppKey)
                + ", authorizationPresent=" + present(authorization) + ", accessTokenPresent="
                + present(accessToken) + "]";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
