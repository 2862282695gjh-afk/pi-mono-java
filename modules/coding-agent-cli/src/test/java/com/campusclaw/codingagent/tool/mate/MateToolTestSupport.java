/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;

/** Mate 工具单元测试的可信运行时夹具。 */
final class MateToolTestSupport {

    static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    static final String SKILL_ID = "skill-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    static final String QUERY_ID = "tool-11111111111111111111111111111111";

    private MateToolTestSupport() {}

    static PreparedAgentRuntime runtime(List<BoundTool> agentTools, List<SkillInfo> skills) {
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn(AGENT_ID);
        when(runtime.metadata()).thenReturn(metadata);
        when(metadata.bindingTools()).thenReturn(agentTools);
        when(runtime.skills()).thenReturn(skills);
        return runtime;
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
