/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

/**
 * 独立开发模式的 Agent 访问授权器，由凭据校验阶段限定调用方范围。
 *
 * <p>公司环境可提供 {@link RuntimeAgentAuthorizer} Bean 接入真实 Agent ACL。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class StandaloneRuntimeAgentAuthorizer implements RuntimeAgentAuthorizer {
    @Override
    public boolean canCreateSession(String agentId, CallerAuthContext caller) {
        return true;
    }
}
