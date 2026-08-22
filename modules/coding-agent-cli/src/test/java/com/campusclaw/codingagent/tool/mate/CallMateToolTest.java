/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CallMateTool}: calls pass through to the client, an
 * error result throws {@link CallMateTool.MateToolExecutionException} so that
 * ToolExecutionPipeline marks the result isError=true.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class CallMateToolTest {

    private MockMateToolClient client;
    private CallMateTool tool;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"));
        client.addTool(new MateToolMeta("delete", "d", Map.of(), Map.of(), false, "deny"));
        tool = new CallMateTool(client, null);
    }

    @Test
    void resolverProvidedCredentialsReachClient() {
        com.campusclaw.codingagent.common.client.mate.MateCredentials expected =
                com.campusclaw.codingagent.common.client.mate.MateCredentials.jwt("hw-id-9", "tok");
        CallMateTool resolved = new CallMateTool(client, toolName -> expected);

        assertDoesNotThrow(() -> resolved.execute("t", Map.of("tool", "query"), null, null));

        assertEquals("hw-id-9", client.lastCallCredentials().xHwId());
        assertEquals("Bearer tok", client.lastCallCredentials().authorization());
    }

    @Test
    void callPassesThroughToClient() {
        var r = assertDoesNotThrow(() -> tool.execute("t", Map.of("tool", "query", "args", Map.of()), null, null));
        assertEquals("query", client.lastCalledTool());
    }

    @Test
    void unknownToolReturnsErrorFromClient() {
        assertThrows(
                CallMateTool.MateToolExecutionException.class,
                () -> tool.execute("t", Map.of("tool", "missing"), null, null));
        assertEquals("missing", client.lastCalledTool());
    }

    @Test
    void missingToolParamThrows() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("t", Map.of(), null, null));
    }
}
