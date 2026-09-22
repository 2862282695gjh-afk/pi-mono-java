/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;

/**
 * 为每个 Agent Session 创建隔离的 Mate 定义解析与执行状态，供代理和 CallTool 共用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolsetFactory {

    private final MateToolClient client;

    public MateToolsetFactory(MateToolClient client) {
        this.client = client;
    }

    public MateToolSessionState createSession(PreparedAgentRuntime runtime, MateCredentials credentials) {
        MateCredentials snapshot = credentials == null ? MateCredentials.empty() : credentials;
        MateToolResolver resolver = new MateToolResolver(client, runtime);
        return new MateToolSessionState(new MateToolExecutor(client, snapshot, resolver));
    }
}
