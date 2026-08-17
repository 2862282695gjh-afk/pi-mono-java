/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.CallMateTool.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.CallMateTool.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CallMateTool} permission decisions.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/17]
 */
class CallMateToolTest {

    private MockMateToolClient client;
    private CallMateTool tool;
    private boolean approvalResult;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"));
        client.addTool(new MateToolMeta("export", "e", Map.of(), Map.of(), false, "ask"));
        client.addTool(new MateToolMeta("delete", "d", Map.of(), Map.of(), false, "deny"));
        approvalResult = true;
        tool = new CallMateTool(client, (t, a, d) -> approvalResult, MateCredentials.appKey("id", "key"));
        tool.updateMeta(List.of(
                new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"),
                new MateToolMeta("export", "e", Map.of(), Map.of(), false, "ask"),
                new MateToolMeta("delete", "d", Map.of(), Map.of(), false, "deny")));
    }

    @Test
    void allowPermissionCallsThrough() throws Exception {
        var r = tool.execute("t", Map.of("tool", "query", "args", Map.of()), null, null);
        assertFalse(hasError(r));
        assertEquals("query", client.lastCalledTool());
    }

    @Test
    void askPermissionApprovedCallsThrough() throws Exception {
        approvalResult = true;
        var r = tool.execute("t", Map.of("tool", "export"), null, null);
        assertFalse(hasError(r));
        assertEquals("export", client.lastCalledTool());
    }

    @Test
    void askPermissionDeniedBlocksCall() throws Exception {
        approvalResult = false;
        var r = tool.execute("t", Map.of("tool", "export"), null, null);
        assertTrue(hasError(r));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void denyPermissionBlocksCall() throws Exception {
        var r = tool.execute("t", Map.of("tool", "delete"), null, null);
        assertTrue(hasError(r));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void missingToolParamReturnsError() throws Exception {
        var r = tool.execute("t", Map.of(), null, null);
        assertTrue(hasError(r));
    }

    @Test
    void credentialsPassedToClient() throws Exception {
        tool.execute("t", Map.of("tool", "query"), null, null);
        assertEquals("id", client.lastCallCredentials().xHwId());
        assertEquals("key", client.lastCallCredentials().xHwAppKey());
    }

    private static boolean hasError(com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult r) {
        return r.content().stream()
                .anyMatch(b -> b instanceof com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent t
                                && t.text().startsWith("Tool denied")
                        || b instanceof com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent t2
                                && (t2.text().startsWith("User denied")
                                        || t2.text().startsWith("Cannot ask")
                                        || t2.text().startsWith("Missing required")));
    }
}
