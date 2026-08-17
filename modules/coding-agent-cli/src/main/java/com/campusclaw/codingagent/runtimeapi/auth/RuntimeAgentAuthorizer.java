/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

/**
 * 判断已认证调用方能否基于指定 Agent 创建 Runtime Session。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface RuntimeAgentAuthorizer {
    boolean canCreateSession(String agentId, CallerAuthContext caller);
}
