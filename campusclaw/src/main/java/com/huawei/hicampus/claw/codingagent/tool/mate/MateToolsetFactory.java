/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.Map;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient;

/**
 * 为每个 Agent Session 创建隔离的 Mate 工具发现与缓存状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolsetFactory {

    private final MateToolClient client;

    public MateToolsetFactory(MateToolClient client) {
        this.client = client;
    }

    public MateToolSessionState createSession(
            String agentId, Map<String, String> skillIdsByName, MateCredentials credentials) {
        MateCredentials snapshot = credentials == null ? MateCredentials.empty() : credentials;
        MateToolSessionCache cache = new MateToolSessionCache();
        MateToolDiscovery discovery = new MateToolDiscovery(client, agentId, skillIdsByName, cache);
        return new MateToolSessionState(client, snapshot, discovery);
    }
}
