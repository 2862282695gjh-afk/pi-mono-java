/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import java.util.List;
import java.util.Objects;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolEntryPoint;

/**
 * 表示由三个入口共同使用的轻量 Agent Session 实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ManagedAgentSession implements AutoCloseable {

    private final PreparedAgentRuntime runtime;

    private final ToolEntryPoint entryPoint;

    private final Agent agent;

    private final List<AgentTool> tools;

    ManagedAgentSession(PreparedAgentRuntime runtime, ToolEntryPoint entryPoint, Agent agent, List<AgentTool> tools) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.tools = List.copyOf(tools);
    }

    public PreparedAgentRuntime runtime() {
        return runtime;
    }

    public ToolEntryPoint entryPoint() {
        return entryPoint;
    }

    public Agent agent() {
        return agent;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    @Override
    public void close() {
        agent.abort();
        agent.clearSteeringQueue();
        agent.clearFollowUpQueue();
    }
}
