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
        CallMateTool resolved = new CallMateTool(client, call -> expected);

        assertDoesNotThrow(() -> resolved.execute("t", Map.of("tool", "query"), null, null));

        assertEquals("hw-id-9", client.lastCallCredentials().xHwId());
        assertEquals("Bearer tok", client.lastCallCredentials().authorization());
    }

    @Test
    void concurrentSessionsGetTheirOwnCredentials() throws Exception {
        java.util.Map<String, com.campusclaw.codingagent.common.client.mate.MateCredentials> bySession =
                new java.util.concurrent.ConcurrentHashMap<>();
        bySession.put("call-a", com.campusclaw.codingagent.common.client.mate.MateCredentials.appKey("id-a", "key-a"));
        bySession.put("call-b", com.campusclaw.codingagent.common.client.mate.MateCredentials.appKey("id-b", "key-b"));
        CallMateTool multiSession = new CallMateTool(client, call -> bySession.get(call.toolCallId()));

        java.util.List<Thread> workers = new java.util.ArrayList<>();
        java.util.List<String> mismatches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < 40; i++) {
            final String callId = (i % 2 == 0) ? "call-a" : "call-b";

            // Each worker verifies through its own recording client to avoid
            // the shared lastCallCredentials field racing between threads.
            MockMateToolClient recordingClient = new MockMateToolClient();
            recordingClient.addTool(new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"));
            CallMateTool sessionTool = new CallMateTool(recordingClient, call -> bySession.get(call.toolCallId()));
            workers.add(Thread.ofPlatform().start(() -> {
                try {
                    sessionTool.execute(callId, Map.of("tool", "query"), null, null);
                    String seen = recordingClient.lastCallCredentials().xHwId();
                    String wanted = bySession.get(callId).xHwId();
                    if (!wanted.equals(seen)) {
                        mismatches.add(callId + " wanted " + wanted + " saw " + seen);
                    }
                } catch (Exception e) {
                    mismatches.add(callId + ": " + e.getMessage());
                }
            }));
        }
        for (Thread w : workers) {
            w.join();
        }
        org.junit.jupiter.api.Assertions.assertTrue(mismatches.isEmpty(), String.join("; ", mismatches));
    }

    @Test
    void resolverCannotMutateToolArgs() throws Exception {
        MockMateToolClient recording = new MockMateToolClient();
        recording.addTool(new MateToolMeta("query", "q", Map.of(), Map.of(), true, "allow"));
        CallMateTool guarded = new CallMateTool(recording, call -> {
            call.args().put("injected", "by-resolver");
            return com.campusclaw.codingagent.common.client.mate.MateCredentials.appKey("id", "key");
        });

        Map<String, Object> originalArgs = new java.util.HashMap<>(Map.of("query", "hi"));
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tool", "query");
        params.put("args", originalArgs);

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> guarded.execute("t", params, null, null));
        org.junit.jupiter.api.Assertions.assertFalse(originalArgs.containsKey("injected"));
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
