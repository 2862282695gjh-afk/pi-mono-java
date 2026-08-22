/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;

/**
 * 按调用解析 Mate 工具凭据的提供者。部署方注册本接口的 Spring Bean 即可
 * 把运行时上下文（如 Loop 下发的 Authorization、请求头、会话）接入
 * {@code callMateTool}；每次工具调用都会重新解析，实现按调用隔离。
 *
 * <p>未注册时的行为：{@code callMateTool} 以 fail-closed 方式拒绝执行
 * （详见 {@code HttpMateToolClient.invokeTool} 的凭据校验），不会发出
 * 未认证请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
public interface MateCredentialResolver {

    /**
     * 解析指定工具调用要透传的凭据。
     *
     * @param tool 待调用的工具标识
     * @return 完整凭据（需满足 {@link MateCredentials#isComplete()}）；返回
     *         null 或残缺凭据时该次调用被拒绝
     */
    MateCredentials resolve(String tool);
}
