/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListMateTool}: agent/skill parameters flow through to
 * the client's two-step query.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class ListMateToolTest {

    private MockMateToolClient client;
    private ListMateTool listMateTool;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"));
        client.addTool(new MateToolMeta("export", "e", Map.of(), Map.of(), false, "allow"));
        client.bindAgent("agent-1", List.of("query", "export"));
        client.bindSkill("skill-1", List.of("query"));
        listMateTool = new ListMateTool(client);
    }

    @Test
    void agentIdIsPassedThroughToClient() throws Exception {
        var r = listMateTool.execute("t", Map.of("agent_id", "agent-1"), null, null);
        String text = asText(r);
        assertTrue(text.contains("query"));
        assertTrue(text.contains("export"));
        assertEquals("agent-1", client.lastListAgentId());
    }

    @Test
    void skillIdIsPassedThroughToClient() throws Exception {
        listMateTool.execute("t", Map.of("skill_id", "skill-1"), null, null);
        assertEquals("skill-1", client.lastListSkillId());
    }

    @Test
    void noIdsReturnsEmptyList() throws Exception {
        listMateTool.execute("t", Map.of(), null, null);
        assertEquals(null, client.lastListAgentId());
        assertEquals(null, client.lastListSkillId());
    }

    private static String asText(com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult r) {
        var sb = new StringBuilder();
        for (var b : r.content()) {
            if (b instanceof com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
