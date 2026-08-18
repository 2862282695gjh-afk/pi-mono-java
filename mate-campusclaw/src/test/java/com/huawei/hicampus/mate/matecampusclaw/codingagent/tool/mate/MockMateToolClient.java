/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;

/**
 * In-memory mock of {@link MateToolClient} for unit tests. Registered tools
 * are resolved through per-agent/per-skill binding lists, mirroring the
 * two-step query of the real client.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class MockMateToolClient implements MateToolClient {

    private final Map<String, List<String>> toolsByAgent = new HashMap<>();

    private final Map<String, List<String>> toolsBySkill = new HashMap<>();

    private final Map<String, MateToolMeta> toolsById = new HashMap<>();

    private String lastListAgentId;

    private String lastListSkillId;

    private String lastCalledTool;

    private MateCredentials lastCallCredentials;

    private MateToolClient.ToolResult overriddenResult;

    /**
     * Registers a tool.
     *
     * @param meta the tool metadata
     */
    public void addTool(MateToolMeta meta) {
        toolsById.put(meta.name(), meta);
    }

    /**
     * Binds a tool ID list to an agent.
     *
     * @param agentId the agent id
     * @param toolIds the bound tool ids
     */
    public void bindAgent(String agentId, List<String> toolIds) {
        toolsByAgent.put(agentId, toolIds);
    }

    /**
     * Binds a tool ID list to a skill.
     *
     * @param skillId the skill id
     * @param toolIds the bound tool ids
     */
    public void bindSkill(String skillId, List<String> toolIds) {
        toolsBySkill.put(skillId, toolIds);
    }

    /**
     * Returns the agent ID received by the last listTools call.
     *
     * @return the agent ID
     */
    public String lastListAgentId() {
        return lastListAgentId;
    }

    /**
     * Returns the skill ID received by the last listTools call.
     *
     * @return the skill ID
     */
    public String lastListSkillId() {
        return lastListSkillId;
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
     * Returns the credentials received by the last callTool call.
     *
     * @return the credentials
     */
    public MateCredentials lastCallCredentials() {
        return lastCallCredentials;
    }

    /**
     * Overrides the result returned by the next callTool invocation regardless
     * of the tool name (used to simulate Mate-side errors).
     *
     * @param result the result to return; null restores normal behavior
     */
    public void overrideCallResult(MateToolClient.ToolResult result) {
        this.overriddenResult = result;
    }

    @Override
    public List<MateToolMeta> listTools(String agentId, String skillId) {
        lastListAgentId = agentId;
        lastListSkillId = skillId;
        List<String> ids = agentId != null ? toolsByAgent.get(agentId) : toolsBySkill.get(skillId);
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
