/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

/**
 * 单元测试使用的内存 {@link MateToolClient}，通过 Agent/Skill 绑定列表模拟真实客户端的两步查询。
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

    private int agentListCalls;

    private int skillListCalls;

    private int executeCalls;

    private Map<String, Object> lastCallArgs;

    /**
     * 注册一个工具。
     *
     * @param meta the tool metadata
     */
    public void addTool(MateToolMeta meta) {
        toolsById.put(meta.toolId(), meta);
    }

    /**
     * 将工具标识列表绑定到 Agent。
     *
     * @param agentId the agent id
     * @param toolIds the bound tool ids
     */
    public void bindAgent(String agentId, List<String> toolIds) {
        toolsByAgent.put(agentId, toolIds);
    }

    /**
     * 将工具标识列表绑定到 Skill。
     *
     * @param skillId the skill id
     * @param toolIds the bound tool ids
     */
    public void bindSkill(String skillId, List<String> toolIds) {
        toolsBySkill.put(skillId, toolIds);
    }

    /**
     * 返回最近一次 Agent 列表调用收到的 Agent 标识。
     *
     * @return the agent ID
     */
    public String lastListAgentId() {
        return lastListAgentId;
    }

    /**
     * 返回最近一次 Skill 列表调用收到的 Skill 标识。
     *
     * @return the skill ID
     */
    public String lastListSkillId() {
        return lastListSkillId;
    }

    /**
     * 返回最近一次 callTool 调用收到的工具标识。
     *
     * @return the tool name
     */
    public String lastCalledTool() {
        return lastCalledTool;
    }

    /**
     * 返回最近一次 callTool 调用收到的凭据。
     *
     * @return the credentials
     */
    public MateCredentials lastCallCredentials() {
        return lastCallCredentials;
    }

    public int agentListCalls() {
        return agentListCalls;
    }

    public int skillListCalls() {
        return skillListCalls;
    }

    public int executeCalls() {
        return executeCalls;
    }

    public Map<String, Object> lastCallArgs() {
        return lastCallArgs;
    }

    /**
     * 覆盖下一次 callTool 的结果，用于模拟 Mate 侧错误。
     *
     * @param result the result to return; null restores normal behavior
     */
    public void overrideCallResult(MateToolClient.ToolResult result) {
        this.overriddenResult = result;
    }

    @Override
    public List<MateToolMeta> listAgentTools(String agentId) {
        agentListCalls++;
        lastListAgentId = agentId;
        return resolve(toolsByAgent.get(agentId));
    }

    @Override
    public List<MateToolMeta> listSkillTools(String skillId) {
        skillListCalls++;
        lastListSkillId = skillId;
        return resolve(toolsBySkill.get(skillId));
    }

    private List<MateToolMeta> resolve(List<String> ids) {
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
        executeCalls++;
        lastCalledTool = tool;
        lastCallArgs = args;
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
