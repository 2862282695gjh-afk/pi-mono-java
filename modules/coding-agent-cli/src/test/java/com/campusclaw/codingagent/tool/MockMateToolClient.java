/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool;

import com.campusclaw.codingagent.tool.CallMateTool.MateCredentials;
import com.campusclaw.codingagent.tool.CallMateTool.MateToolClient;
import com.campusclaw.codingagent.tool.CallMateTool.MateToolMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory mock of {@link MateToolClient} for unit tests.
 * Records the last credentials it received so tests can assert credential passing.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/17]
 */
public class MockMateToolClient implements MateToolClient {

    private final Map<String, List<String>> authorizedByAgent = new HashMap<>();
    private final Map<String, List<String>> authorizedBySkill = new HashMap<>();
    private final Map<String, MateToolMeta> toolsById = new HashMap<>();

    private MateCredentials lastListCredentials;
    private MateCredentials lastCallCredentials;
    private String lastCalledTool;

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

    @Override
    public List<MateToolMeta> listTools(String agentId, String skillId,
            MateCredentials credentials) {
        lastListCredentials = credentials;
        List<String> ids = agentId != null
                ? authorizedByAgent.get(agentId)
                : authorizedBySkill.get(skillId);
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
    public ToolResult callTool(String tool, Map<String, Object> args,
            MateCredentials credentials) {
        lastCalledTool = tool;
        lastCallCredentials = credentials;
        MateToolMeta meta = toolsById.get(tool);
        if (meta == null) {
            return new ToolResult("unknown tool: " + tool, null, true);
        }
        return new ToolResult("mock:" + tool, null, false);
    }
}
