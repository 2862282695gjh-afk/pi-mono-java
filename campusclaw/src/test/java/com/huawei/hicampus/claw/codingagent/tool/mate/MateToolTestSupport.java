/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptTestFixtures;

/**
 * Mate 工具单元测试的可信运行时夹具。
 */
final class MateToolTestSupport {

    static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    static final String SKILL_ID = "skill-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    static final String QUERY_ID = "tool-11111111111111111111111111111111";

    private MateToolTestSupport() {}

    static PreparedAgentRuntime runtime(List<BoundTool> agentTools, List<SkillInfo> skills) {
        AgentRuntime metadata = new AgentRuntime(
                List.of(),
                List.of(),
                agentTools,
                List.of(),
                List.of(),
                "Agent",
                true,
                AGENT_ID,
                "agent",
                "prompt",
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(
                AGENT_ID, Path.of("agent", AGENT_ID), metadata, skills, AgentPromptTestFixtures.prompts("prompt"));
    }

    static SkillInfo skill(List<BoundTool> tools) {
        return new SkillInfo(
                "research",
                SKILL_ID,
                "1.0.0",
                "Research",
                null,
                "---\nname: research\ndescription: Research\n---\nInstructions",
                tools,
                List.of(),
                List.of(),
                List.of());
    }

    static BoundTool binding(String id, String name, String displayName, String permission) {
        return new BoundTool("binding", displayName, id, null, name, permission, null, "1.0.0");
    }

    static MateToolMeta meta(String id, String name, Map<String, Object> schema) {
        return new MateToolMeta(id, name, "Query records", schema, Map.of(), false, "allow");
    }

    static Map<String, Object> requiredValueSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string")),
                "required",
                List.of("value"),
                "additionalProperties",
                false);
    }
}
