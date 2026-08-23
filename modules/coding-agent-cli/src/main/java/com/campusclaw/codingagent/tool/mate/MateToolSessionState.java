/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import com.campusclaw.codingagent.common.client.mate.MateToolClient;

/**
 * 保存单个 Session 共享的 Mate 发现状态并按需创建工具实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolSessionState {

    private final MateToolClient client;

    private final MateCredentialResolver credentialResolver;

    private final MateToolDiscovery discovery;

    MateToolSessionState(
            MateToolClient client, MateCredentialResolver credentialResolver, MateToolDiscovery discovery) {
        this.client = client;
        this.credentialResolver = credentialResolver;
        this.discovery = discovery;
    }

    public ListMateToolsTool createListTool() {
        return new ListMateToolsTool(discovery);
    }

    public CallMateTool createCallTool() {
        return new CallMateTool(client, credentialResolver, discovery);
    }
}
