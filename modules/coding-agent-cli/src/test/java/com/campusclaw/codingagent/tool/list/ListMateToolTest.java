/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.tool.call.CallMateTool.MateCredentials;
import com.campusclaw.codingagent.tool.call.CallMateTool.MateToolMeta;
import com.campusclaw.codingagent.tool.call.MockMateToolClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListMateTool}: authorization filtering, cache refresh,
 * and credential passing.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/17]
 */
class ListMateToolTest {

    private MockMateToolClient client;
    private CallMateTool callMateTool;
    private boolean approvalResult;
    private ListMateTool listMateTool;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"));
        client.addTool(new MateToolMeta("chart", "c", Map.of(), Map.of(), true, "allow"));
        client.addTool(new MateToolMeta("export", "e", Map.of(), Map.of(), false, "ask"));
        client.addTool(new MateToolMeta("delete", "d", Map.of(), Map.of(), false, "deny"));
        client.authorizeAgent("agent-1", List.of("query", "chart", "export"));
        client.authorizeSkill("skill-1", List.of("query", "chart"));
        approvalResult = true;
        callMateTool = new CallMateTool(client, (t, a, d) -> approvalResult, MateCredentials.appKey("id", "key"));
        listMateTool = new ListMateTool(client, callMateTool);
    }

    @Test
    void agentFilterReturnsOnlyAuthorizedTools() throws Exception {
        var r = listMateTool.execute("t", Map.of("agent_id", "agent-1"), null, null);
        String text = asText(r);
        assertTrue(text.contains("query"));
        assertTrue(text.contains("chart"));
        assertTrue(text.contains("export"));
        assertTrue(!text.contains("delete"));
    }

    @Test
    void skillFilterReturnsOnlyAuthorizedTools() throws Exception {
        var r = listMateTool.execute("t", Map.of("skill_id", "skill-1"), null, null);
        String text = asText(r);
        assertTrue(text.contains("query"));
        assertTrue(!text.contains("export"));
    }

    @Test
    void refreshesCallMateToolCache() throws Exception {
        listMateTool.execute("t", Map.of("agent_id", "agent-1"), null, null);

        // export IS in agent-1's authorized list → its "ask" meta is cached
        // → approvalUI (auto-approve) fires → call goes through
        approvalResult = true;
        var r = callMateTool.execute("t", Map.of("tool", "export"), null, null);
        assertTrue(asText(r).contains("mock:export"));
        assertEquals("export", client.lastCalledTool());
    }

    @Test
    void credentialsSharedWithCallMateTool() throws Exception {
        listMateTool.execute("t", Map.of("agent_id", "agent-1"), null, null);
        assertEquals("id", client.lastListCredentials().xHwId());
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
