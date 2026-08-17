/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool.MateToolMeta;

/**
 * In-memory mock of {@link MateToolClient} for unit tests.
 * Records the last credentials it received so tests can assert credential passing.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 */
public class MockMateToolClient implements MateToolClient {

    private final Map<String, List<String>> authorizedByAgent = new HashMap<>();
    private final Map<String, List<String>> authorizedBySkill = new HashMap<>();
    private final Map<String, MateToolMeta> toolsById = new HashMap<>();

    private MateCredentials lastListCredentials;
    private MateCredentials lastCallCredentials;
    private String lastCalledTool;
    private CallMateTool.MateToolClient.ToolResult overriddenResult;

    /**
     * Registers a tool.
     *
     * @param meta the tool metadata
     */
    public void addTool(MateToolMeta meta) {
        toolsById.put(meta.name(), meta);
    }

    /**
     * Authorizes a list of tool ids for an agent.
     *
     * @param agentId the agent id
     * @param toolIds the tool ids
     */
    public void authorizeAgent(String agentId, List<String> toolIds) {
        authorizedByAgent.put(agentId, toolIds);
    }

    /**
     * Authorizes a list of tool ids for a skill.
     *
     * @param skillId the skill id
     * @param toolIds the tool ids
     */
    public void authorizeSkill(String skillId, List<String> toolIds) {
        authorizedBySkill.put(skillId, toolIds);
    }

    /**
     * Returns the credentials received by the last listTools call.
     *
     * @return the credentials
     */
    public MateCredentials lastListCredentials() {
        return lastListCredentials;
    }

    /**
     * Returns the credentials received by the last callTool call.
     *
     * @return the credentials
     */
    public MateCredentials lastCallCredentials() {
        return lastCallCredentials;
    }

    /**
     * Returns the tool name received by the last callTool call.
     *
     * @return the tool name
     */
    public String lastCalledTool() {
        return lastCalledTool;
    }

    /**
     * Overrides the result returned by the next callTool invocation regardless
     * of the tool name (used to simulate Mate-side errors).
     *
     * @param result the result to return; null restores normal behavior
     */
    public void overrideCallResult(CallMateTool.MateToolClient.ToolResult result) {
        this.overriddenResult = result;
    }

    @Override
    public List<MateToolMeta> listTools(String agentId, String skillId, MateCredentials credentials) {
        lastListCredentials = credentials;
        List<String> ids = agentId != null ? authorizedByAgent.get(agentId) : authorizedBySkill.get(skillId);
        List<MateToolMeta> result = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                MateToolMeta meta = toolsById.get(id);
                if (meta != null) {
                    result.add(meta);
                }
            }
        }
        return result;
    }

    @Override
    public ToolResult callTool(String tool, Map<String, Object> args, MateCredentials credentials) {
        lastCalledTool = tool;
        lastCallCredentials = credentials;
        if (overriddenResult != null) {
            return overriddenResult;
        }
        MateToolMeta meta = toolsById.get(tool);
        if (meta == null) {
            return new ToolResult("unknown tool: " + tool, null, true);
        }
        return new ToolResult("mock:" + tool, null, false);
    }
}
