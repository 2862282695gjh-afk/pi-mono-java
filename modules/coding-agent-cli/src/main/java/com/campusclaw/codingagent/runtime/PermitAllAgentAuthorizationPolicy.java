/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import org.springframework.stereotype.Component;

/**
 * Spring wiring of {@link AgentAuthorizationPolicy#PERMIT_ALL}: until tenant
 * and user identity flow through the entry request, direct binding is the
 * only security boundary and every principal is authorized.
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
