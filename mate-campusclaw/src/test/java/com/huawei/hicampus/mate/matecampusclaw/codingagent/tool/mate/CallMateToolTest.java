/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;

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
        client.addTool(
                new MateToolMeta("tool-11111111111111111111111111111111", "q", Map.of(), Map.of(), true, "allow"));
        client.addTool(
                new MateToolMeta("tool-33333333333333333333333333333333", "d", Map.of(), Map.of(), false, "deny"));
        tool = new CallMateTool(client, null);
    }

    @Test
    void resolverProvidedCredentialsReachClient() {
        com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials expected =
                com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials.jwt("hw-id-9", "tok");
        CallMateTool resolved = new CallMateTool(client, call -> expected);

        assertDoesNotThrow(
                () -> resolved.execute("t", Map.of("tool", "tool-11111111111111111111111111111111"), null, null));

        assertEquals("hw-id-9", client.lastCallCredentials().xHwId());
        assertEquals("Bearer tok", client.lastCallCredentials().authorization());
    }

    @Test
    void concurrentSessionsGetTheirOwnCredentials() throws Exception {
        java.util.Map<String, com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials> bySession =
                new java.util.concurrent.ConcurrentHashMap<>();
        bySession.put("call-a", com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials.appKey("id-a", "key-a"));
        bySession.put("call-b", com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials.appKey("id-b", "key-b"));
        CallMateTool multiSession = new CallMateTool(client, call -> bySession.get(call.toolCallId()));

        java.util.List<Thread> workers = new java.util.ArrayList<>();
        java.util.List<String> mismatches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < 40; i++) {
            final String callId = (i % 2 == 0) ? "call-a" : "call-b";

            // Each worker verifies through its own recording client to avoid
            // the shared lastCallCredentials field racing between threads.
            MockMateToolClient recordingClient = new MockMateToolClient();
            recordingClient.addTool(
                    new MateToolMeta("tool-11111111111111111111111111111111", "q", Map.of(), Map.of(), true, "allow"));
            CallMateTool sessionTool = new CallMateTool(recordingClient, call -> bySession.get(call.toolCallId()));
            workers.add(Thread.ofPlatform().start(() -> {
                try {
                    sessionTool.execute(callId, Map.of("tool", "tool-11111111111111111111111111111111"), null, null);
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
        recording.addTool(
                new MateToolMeta("tool-11111111111111111111111111111111", "q", Map.of(), Map.of(), true, "allow"));
        CallMateTool guarded = new CallMateTool(recording, call -> {
            call.args().put("injected", "by-resolver");
            return com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials.appKey("id", "key");
        });

        Map<String, Object> originalArgs = new java.util.HashMap<>(Map.of("query", "hi"));
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tool", "tool-11111111111111111111111111111111");
        params.put("args", originalArgs);

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> guarded.execute("t", params, null, null));
        org.junit.jupiter.api.Assertions.assertFalse(originalArgs.containsKey("injected"));
    }

    @Test
    void resolverCannotMutateNestedMapArgs() throws Exception {
        Map<String, Object> nested = new java.util.HashMap<>(Map.of("flag", false));
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("options", nested);
        Map<String, Object> observed = runResolverMutation(args, call -> {
            ((Map<String, Object>) call.args().get("options")).put("dangerous", true);
            return null;
        });

        org.junit.jupiter.api.Assertions.assertEquals(Map.of("flag", false), observed.get("options"));
    }

    @Test
    void resolverCannotMutateNestedListArgs() throws Exception {
        java.util.List<Object> nestedList = new java.util.ArrayList<>(List.of("a"));
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("tags", nestedList);
        Map<String, Object> observed = runResolverMutation(args, call -> {
            ((List<Object>) call.args().get("tags")).add("injected");
            return null;
        });

        org.junit.jupiter.api.Assertions.assertEquals(List.of("a"), observed.get("tags"));
    }

    /**
     * 以指定参数执行一次带篡改 resolver 的调用，断言快照只读（抛
     * UnsupportedOperationException）并返回原始嵌套结构供值断言。
     *
     * @param args 待传入的工具参数（含待篡改的嵌套结构）
     * @param mutatingResolver 执行篡改尝试的解析器
     * @return 原始 args（未被修改）
     * @throws Exception 工具执行失败时抛出
     */
    private Map<String, Object> runResolverMutation(Map<String, Object> args, MateCredentialResolver mutatingResolver)
            throws Exception {
        MockMateToolClient recording = new MockMateToolClient();
        recording.addTool(
                new MateToolMeta("tool-11111111111111111111111111111111", "q", Map.of(), Map.of(), true, "allow"));
        CallMateTool guarded = new CallMateTool(recording, mutatingResolver);
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tool", "tool-11111111111111111111111111111111");
        params.put("args", args);

        // The read-only snapshot throws on any mutation attempt, before the
        // mutation can reach the original structures.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> guarded.execute("t", params, null, null));
        return args;
    }

    @Test
    void callPassesThroughToClient() {
        var r = assertDoesNotThrow(() -> tool.execute(
                "t", Map.of("tool", "tool-11111111111111111111111111111111", "args", Map.of()), null, null));
        assertEquals("tool-11111111111111111111111111111111", client.lastCalledTool());
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
