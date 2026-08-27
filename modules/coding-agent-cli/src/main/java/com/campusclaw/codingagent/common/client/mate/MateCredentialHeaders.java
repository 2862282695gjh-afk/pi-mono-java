/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client.mate;

/**
 * 定义 Runtime 与 Mate 工具调用共享的凭据请求头名称。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public final class MateCredentialHeaders {

    public static final String X_HW_ID = "X-HW-ID";

    public static final String X_HW_APPKEY = "X-HW-APPKEY";

    public static final String AUTHORIZATION = "Authorization";

    public static final String ACCESS_TOKEN = "access-token";

    private MateCredentialHeaders() {}
}
