/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;

/**
 * 为每个 Agent Session 创建隔离的 Mate 工具发现与缓存状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolsetFactory {

    private final MateToolClient client;

    private final MateCredentialResolver credentialResolver;

    public MateToolsetFactory(MateToolClient client, MateCredentialResolver credentialResolver) {
        this.client = client;
        this.credentialResolver = credentialResolver;
    }

    public MateToolSessionState createSession(String agentId, Map<String, String> skillIdsByName) {
        MateToolSessionCache cache = new MateToolSessionCache();
        MateToolDiscovery discovery = new MateToolDiscovery(client, agentId, skillIdsByName, cache);
        return new MateToolSessionState(client, credentialResolver, discovery);
    }
}
