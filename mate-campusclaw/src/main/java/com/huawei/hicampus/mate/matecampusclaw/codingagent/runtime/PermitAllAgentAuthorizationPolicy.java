/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import org.springframework.stereotype.Component;

/**
 * {@link AgentAuthorizationPolicy#PERMIT_ALL} 的 Spring 装配：在租户与用户
 * 身份贯通入口请求之前，直接绑定是唯一安全边界，所有主体均放行。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class PermitAllAgentAuthorizationPolicy implements AgentAuthorizationPolicy {

    @Override
    public boolean isAuthorized(AgentPrincipal principal, String agentId) {
        return true;
    }
}
