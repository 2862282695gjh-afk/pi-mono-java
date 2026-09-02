/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.common.dto;

import java.util.HashMap;
import java.util.Map;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentialHeaders;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

/**
 * 每次请求 Mate 内网网关携带的 Header 信息。
 *
 * <p>敏感字段(token、凭据、cookie)以 {@link ToString.Exclude} 排除在
 * {@link #toString()} 之外，不会泄漏进日志。Mate 工具发现请求不填充执行凭据，只有工具执行
 * 请求按本次 Agent 执行上下文填充凭据字段。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@Builder(toBuilder = true)
public class RequestHeaderInfo {
    @ToString.Exclude
    private String accessToken;

    private String clientIp;
    private String locale;
    private String xForward;
    private String appId;

    /**
     * X-HW-ID 凭据 Header;由调用方(agent 下发)可选提供。
     */
    private String xHwId;

    /**
     * X-HW-APPKEY 凭据 Header;AppKey 模式与 xHwId 成对提供。
     */
    @ToString.Exclude
    private String xHwAppKey;

    @ToString.Exclude
    private String appKey;

    @ToString.Exclude
    private String cookie;

    @ToString.Exclude
    private String csrfToken;

    @ToString.Exclude
    private String authorization;

    @ToString.Exclude
    private String xAuthToken;

    @ToString.Exclude
    private String roaRand;

    private String xAgentId;
    private String agentTenantId;
    private String agentUserId;
    private String a2aVersion;
    private String xPlaneType;

    @ToString.Exclude
    private Map<String, String> customHeaders;

    /**
     * 按 Mate 内部网关约定把字段映射为 HTTP Header；空值由请求构建器忽略，
     * {@code customHeaders} 条目按原值合并。
     *
     * @return Header 名称到值的映射，值可能为空
     */
    public Map<String, String> toHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MateCredentialHeaders.ACCESS_TOKEN, accessToken);
        headers.put("X-Client-IP", clientIp);
        headers.put("X-Locale", locale);
        headers.put("X-Forward", xForward);
        headers.put("X-App-Id", appId);
        headers.put(MateCredentialHeaders.X_HW_ID, xHwId);
        headers.put(MateCredentialHeaders.X_HW_APPKEY, xHwAppKey);
        headers.put("X-App-Key", appKey);
        headers.put("Cookie", cookie);
        headers.put("X-Csrf-Token", csrfToken);
        headers.put(MateCredentialHeaders.AUTHORIZATION, authorization);
        headers.put("X-Auth-Token", xAuthToken);
        headers.put("X-Roa-Rand", roaRand);
        headers.put("X-Agent-Id", xAgentId);
        headers.put("X-Agent-Tenant-Id", agentTenantId);
        headers.put("X-Agent-User-Id", agentUserId);
        headers.put("X-A2a-Version", a2aVersion);
        headers.put("X-Plane-Type", xPlaneType);
        if (customHeaders != null) {
            customHeaders.forEach(headers::putIfAbsent);
        }
        return headers;
    }
}
