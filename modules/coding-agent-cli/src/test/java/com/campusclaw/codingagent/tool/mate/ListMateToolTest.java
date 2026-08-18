/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListMateTool}: tool-id based querying.
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
        client.addTool(new MateToolMeta("export", "e", Map.of(), Map.of(), false, "ask"));
        listMateTool = new ListMateTool(client);
    }

    @Test
    void agentIdIsPassedAsToolIdToQuery() throws Exception {
        var r = listMateTool.execute("t", Map.of("agent_id", "query"), null, null);
        String text = asText(r);
        assertTrue(text.contains("query"));
        assertEquals(List.of("query"), client.lastQueriedToolIds());
    }

    @Test
    void skillIdIsPassedAsToolIdToQuery() throws Exception {
        listMateTool.execute("t", Map.of("skill_id", "export"), null, null);
        assertEquals(List.of("export"), client.lastQueriedToolIds());
    }

    @Test
    void noIdsQueriesEmptyList() throws Exception {
        listMateTool.execute("t", Map.of(), null, null);
        assertEquals(List.of(), client.lastQueriedToolIds());
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
