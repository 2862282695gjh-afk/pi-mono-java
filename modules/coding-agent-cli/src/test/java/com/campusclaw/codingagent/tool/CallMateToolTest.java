/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.tool.CallMateTool.MateCredentials;
import com.campusclaw.codingagent.tool.CallMateTool.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CallMateTool} permission decisions: allow calls through,
 * ask/deny throw {@link CallMateTool.MateToolExecutionException} so that
 * ToolExecutionPipeline marks the result isError=true.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
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
    void allowPermissionCallsThrough() {
        var r = assertDoesNotThrow(() -> tool.execute("t", Map.of("tool", "query", "args", Map.of()), null, null));
        assertEquals("query", client.lastCalledTool());
    }

    @Test
    void askPermissionApprovedCallsThrough() {
        approvalResult = true;
        assertDoesNotThrow(() -> tool.execute("t", Map.of("tool", "export"), null, null));
        assertEquals("export", client.lastCalledTool());
    }

    @Test
    void askPermissionDeniedThrows() {
        approvalResult = false;
        assertThrows(
                CallMateTool.MateToolExecutionException.class,
                () -> tool.execute("t", Map.of("tool", "export"), null, null));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void denyPermissionThrows() {
        assertThrows(
                CallMateTool.MateToolExecutionException.class,
                () -> tool.execute("t", Map.of("tool", "delete"), null, null));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void missingToolParamThrows() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("t", Map.of(), null, null));
    }

    @Test
    void credentialsPassedToClient() throws Exception {
        tool.execute("t", Map.of("tool", "query"), null, null);
        assertEquals("id", client.lastCallCredentials().xHwId());
        assertEquals("key", client.lastCallCredentials().xHwAppKey());
    }

    @Test
    void missingRequiredArgumentThrowsBeforeRemoteCall() {
        client.addTool(new MateToolMeta(
                "needArg",
                "n",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("query"),
                        "properties",
                        Map.of("query", Map.of("type", "string"))),
                Map.of(),
                true,
                "allow"));
        tool.updateMeta(List.of(new MateToolMeta(
                "needArg",
                "n",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("query"),
                        "properties",
                        Map.of("query", Map.of("type", "string"))),
                Map.of(),
                true,
                "allow")));
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute("t", Map.of("tool", "needArg", "args", Map.of()), null, null));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("missing required"));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void wrongArgumentTypeThrowsBeforeRemoteCall() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("count"),
                "properties", Map.of("count", Map.of("type", "integer")));
        client.addTool(new MateToolMeta("typed", "t2", schema, Map.of(), true, "allow"));
        tool.updateMeta(List.of(new MateToolMeta("typed", "t2", schema, Map.of(), true, "allow")));
        var ex = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute("t", Map.of("tool", "typed", "args", Map.of("count", "not-a-number")), null, null));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("expects integer"));
        assertEquals(null, client.lastCalledTool());
    }
}
