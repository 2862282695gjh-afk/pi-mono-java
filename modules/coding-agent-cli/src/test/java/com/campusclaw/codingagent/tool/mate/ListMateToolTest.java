/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

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
        var r = listMateTool.execute("t", Map.of(), null, null);
        assertNull(client.lastListAgentId());
        assertNull(client.lastListSkillId());
        assertTrue(asText(r).contains("0 tool(s)"));
    }

    private static String asText(com.campusclaw.agent.tool.AgentToolResult r) {
        var sb = new StringBuilder();
        for (var b : r.content()) {
            if (b instanceof com.campusclaw.ai.types.TextContent t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
