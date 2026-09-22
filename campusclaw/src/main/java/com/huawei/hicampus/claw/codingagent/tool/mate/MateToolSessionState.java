/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.List;

import com.huawei.hicampus.claw.agent.tool.AgentTool;

/**
 * 保存单个 Session 共享的 Mate 执行器并按需创建工具实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolSessionState {

    private final MateToolExecutor executor;

    MateToolSessionState(MateToolExecutor executor) {
        this.executor = executor;
    }

    public List<AgentTool> createAgentToolAdapters() {
        return executor.createAgentToolAdapters();
    }

    public CallTool createCallTool() {
        return new CallTool(executor);
    }

    public SearchTools createSearchTools() {
        return new SearchTools(executor);
    }
}
