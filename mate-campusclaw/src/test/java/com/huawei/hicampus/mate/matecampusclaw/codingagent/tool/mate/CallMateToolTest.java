/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CallMateTool} 单元测试：工具名经会话缓存映射为工具标识、凭据按调用
 * 解析、缓存未命中/无缓存拒绝、并发隔离与参数防篡改。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
class CallMateToolTest {

    private static final String QUERY_ID = "tool-11111111111111111111111111111111";

    private MockMateToolClient client;
    private MateToolSessionCache cache;
    private CallMateTool tool;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta(QUERY_ID, "query", "q", Map.of(), Map.of(), true, "allow"));
        cache = new MateToolSessionCache();
        cache.refresh(List.of(new MateToolMeta(QUERY_ID, "query", "q", Map.of(), Map.of(), true, "allow")));
        tool = new CallMateTool(client, null, cache);
    }

    @Test
    void toolNameIsMappedToToolIdViaSessionCache() throws Exception {
        var r = tool.execute("t", Map.of("tool", "query", "args", Map.of()), null, null);

        assertTrue(asText(r).contains("mock:" + QUERY_ID));
        assertEquals(QUERY_ID, client.lastCalledTool());
    }

    @Test
    void unknownToolNameIsRejectedBeforeRemoteCall() {
        assertThrows(
                CallMateTool.MateToolExecutionException.class,
                () -> tool.execute("t", Map.of("tool", "no-such-name"), null, null));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void singletonWithoutCacheIsRejectedWithHint() {
        CallMateTool singleton = new CallMateTool(client, null, null);
        assertThrows(
                CallMateTool.MateToolExecutionException.class,
                () -> singleton.execute("t", Map.of("tool", "query"), null, null));
        assertEquals(null, client.lastCalledTool());
    }

    @Test
    void missingToolParamThrows() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("t", Map.of(), null, null));
    }

    @Test
    void listMateToolRefreshReplacesCacheEntries() throws Exception {
        // New binding set arrives via listMateTool: old name must be evicted.
        client.addTool(new MateToolMeta(
                "tool-22222222222222222222222222222222", "chart", "c", Map.of(), Map.of(), true, "allow"));
        cache.refresh(List.of(new MateToolMeta(
                "tool-22222222222222222222222222222222", "chart", "c", Map.of(), Map.of(), true, "allow")));
        assertThrows(
                CallMateTool.MateToolExecutionException.class,
                () -> tool.execute("t", Map.of("tool", "query"), null, null));
        tool.execute("t", Map.of("tool", "chart"), null, null);
        assertEquals("tool-22222222222222222222222222222222", client.lastCalledTool());
    }

    @Test
    void resolverProvidedCredentialsReachClient() {
        MateCredentials expected = MateCredentials.jwt("hw-id-9", "tok");
        CallMateTool resolved = new CallMateTool(client, call -> expected, cache);

        assertDoesNotThrow(() -> resolved.execute("t", Map.of("tool", "query"), null, null));

        assertEquals("hw-id-9", client.lastCallCredentials().xHwId());
        assertEquals("Bearer tok", client.lastCallCredentials().authorization());
    }

    @Test
    void concurrentSessionsGetTheirOwnCredentials() throws Exception {
        Map<String, MateCredentials> bySession = new java.util.concurrent.ConcurrentHashMap<>();
        bySession.put("call-a", MateCredentials.appKey("id-a", "key-a"));
        bySession.put("call-b", MateCredentials.appKey("id-b", "key-b"));

        java.util.List<Thread> workers = new java.util.ArrayList<>();
        List<String> mismatches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < 40; i++) {
            final String callId = (i % 2 == 0) ? "call-a" : "call-b";

            // Each worker verifies through its own recording client to avoid
            // the shared lastCallCredentials field racing between threads.
            MockMateToolClient recordingClient = new MockMateToolClient();
            recordingClient.addTool(new MateToolMeta(QUERY_ID, "query", "q", Map.of(), Map.of(), true, "allow"));
            CallMateTool sessionTool =
                    new CallMateTool(recordingClient, call -> bySession.get(call.toolCallId()), cache);
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
        assertTrue(mismatches.isEmpty(), String.join("; ", mismatches));
    }

    @Test
    void resolverCannotMutateToolArgs() throws Exception {
        Map<String, Object> originalArgs = new java.util.HashMap<>(Map.of("query", "hi"));
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tool", "query");
        params.put("args", originalArgs);
        CallMateTool guarded = new CallMateTool(
                client,
                call -> {
                    call.args().put("injected", "by-resolver");
                    return null;
                },
                cache);

        assertThrows(UnsupportedOperationException.class, () -> guarded.execute("t", params, null, null));
        assertTrue(!originalArgs.containsKey("injected"));
    }

    @Test
    void resolverCannotMutateNestedMapArgs() throws Exception {
        Map<String, Object> nested = new java.util.HashMap<>(Map.of("flag", false));
        Map<String, Object> observed = runResolverMutation(new java.util.HashMap<>(Map.of("options", nested)), call -> {
            ((Map<String, Object>) call.args().get("options")).put("dangerous", true);
            return null;
        });

        assertEquals(Map.of("flag", false), observed.get("options"));
    }

    @Test
    void resolverCannotMutateNestedListArgs() throws Exception {
        java.util.List<Object> nestedList = new java.util.ArrayList<>(List.of("a"));
        Map<String, Object> observed =
                runResolverMutation(new java.util.HashMap<>(Map.of("tags", nestedList)), call -> {
                    ((List<Object>) call.args().get("tags")).add("injected");
                    return null;
                });

        assertEquals(List.of("a"), observed.get("tags"));
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
        recording.addTool(new MateToolMeta(QUERY_ID, "query", "q", Map.of(), Map.of(), true, "allow"));
        CallMateTool guarded = new CallMateTool(recording, mutatingResolver, cache);
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tool", "query");
        params.put("args", args);

        // The read-only snapshot throws on any mutation attempt, before the
        // mutation can reach the original structures.
        assertThrows(UnsupportedOperationException.class, () -> guarded.execute("t", params, null, null));
        return args;
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
