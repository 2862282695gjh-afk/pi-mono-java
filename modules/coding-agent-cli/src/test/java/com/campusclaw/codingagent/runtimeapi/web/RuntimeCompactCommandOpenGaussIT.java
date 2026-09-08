/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CLIENT;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.awaitHealth;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.createSession;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.eventsUri;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.freePort;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.getSession;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.loadConfiguration;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.prepareRuntimeFiles;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.send;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.sessionUri;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.startRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.RuntimeProcess;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SessionViewDTO;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 使用真实服务进程、HTTP 和 openGauss 验证压缩结果、客户端断线及新 JVM 恢复。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCompactCommandOpenGaussIT {
    private static final String OLD_TEXT = "compact-old-turn-marker";

    // 超过默认 20000 Token 保留窗口，确保压缩第一轮并原样保留第二轮。
    private static final String KEPT_TEXT = "compact-retained-turn-marker " + "r".repeat(84_000);

    private static final String NEXT_TEXT = "compact-after-restart-marker";

    private static final String SUMMARY = "process-level answer 3";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testRealSummaryAndNewJvmRecoveryAfterOptionalClientDisconnect(boolean disconnect, @TempDir Path directory)
            throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        int modelPort = freePort();
        prepareRuntimeFiles(directory);
        Files.writeString(
                directory.resolve("application.properties"),
                "campusclaw.runtime.execution.max-active=1\n",
                StandardCharsets.UTF_8);
        ModelStub model = new ModelStub(modelPort);
        try (model) {
            model.start();
            int port = freePort();
            String sessionId;
            JsonNode savedHistory;
            SessionViewDTO savedSession;
            RuntimeProcess first = startRuntime(config, directory, port, modelPort);
            try (first) {
                awaitHealth(first, port);
                sessionId = createSession(port);
                JsonNode retainedEntry = seedHistory(port, sessionId, model);
                String competitor = createSession(port);
                compactAndAwait(port, sessionId, competitor, model, disconnect);
                assertCompactionStorage(
                        config, sessionId, retainedEntry.path("entryId").asText());
                assertReleasedResources(port, sessionId, competitor, model);
                savedHistory = history(port, sessionId);
                savedSession = getSession(port, sessionId);
                assertThat(savedSession.result().path("state").asText()).isEqualTo("idle");
            }
            assertThat(first.process().isAlive()).isFalse();
            assertRestartedInput(config, directory, modelPort, sessionId, savedSession, savedHistory, model);
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void testEmptyHistoryReturnsJsonWithoutPersistentChangesOrModelRequest(@TempDir Path directory) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        int port = freePort();
        int modelPort = freePort();
        prepareRuntimeFiles(directory);
        ModelStub model = new ModelStub(modelPort);
        RuntimeProcess runtime;
        try (model) {
            model.start();
            runtime = startRuntime(config, directory, port, modelPort);
            try (runtime) {
                awaitHealth(runtime, port);
                String sessionId = createSession(port);
                SessionViewDTO before = getSession(port, sessionId);
                var databaseBefore = persistentState(config, sessionId);
                assertThat(history(port, sessionId)).isEmpty();
                assertCompactJson(send(compactRequest(port, sessionId)), false);
                assertThat(getSession(port, sessionId)).isEqualTo(before);
                assertThat(persistentState(config, sessionId)).isEqualTo(databaseBefore);
                assertThat(history(port, sessionId)).isEmpty();
                assertThat(model.requestCount()).isZero();
            }
            assertThat(runtime.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    private static JsonNode seedHistory(int port, String sessionId, ModelStub model) throws Exception {
        assertUserStream(submitUser(port, sessionId, OLD_TEXT), OLD_TEXT, "process-level answer");
        assertUserStream(submitUser(port, sessionId, KEPT_TEXT), KEPT_TEXT, "process-level answer 2");
        JsonNode entries = history(port, sessionId);
        assertThat(entries).hasSize(4);
        assertThat(entries.get(2).path("message").asText()).isEqualTo(KEPT_TEXT);
        assertThat(entries.get(2).path("type").asText()).isEqualTo("user.message");
        assertThat(model.requestCount()).isEqualTo(2);
        return entries.get(2);
    }

    private static void compactAndAwait(
            int port, String sessionId, String competitor, ModelStub model, boolean disconnect) throws Exception {
        var gate = model.blockNextResponse();
        try {
            if (disconnect) {
                try (Socket client = openCompactSocket(port, sessionId)) {
                    gate.awaitRequest();
                    assertPendingCompaction(port, sessionId, model);
                    assertCapacityExhausted(port, competitor, model);
                    client.setSoLinger(true, 0);
                    client.close();
                    assertThat(client.isClosed()).isTrue();
                }
                gate.release();
            } else {
                CompletableFuture<HttpResponse<String>> response = CLIENT.sendAsync(
                        compactRequest(port, sessionId), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                gate.awaitRequest();
                assertPendingCompaction(port, sessionId, model);
                assertCapacityExhausted(port, competitor, model);
                assertThat(response).isNotDone();
                gate.release();
                assertCompactJson(response.get(10, TimeUnit.SECONDS), true);
            }
            awaitCompaction(port, sessionId);
            assertThat(model.requestCount()).isEqualTo(3);
        } finally {
            gate.release();
        }
    }

    private static void assertPendingCompaction(int port, String sessionId, ModelStub model) throws Exception {
        assertThat(getSession(port, sessionId).result().path("state").asText()).isEqualTo("running");
        assertThat(history(port, sessionId)).hasSize(4);
        String summaryRequest = model.lastRequest().path("messages").toString();
        assertThat(summaryRequest)
                .contains("context summarization assistant", OLD_TEXT, "process-level answer")
                .doesNotContain("compact-retained-turn-marker");
    }

    private static void assertCapacityExhausted(int port, String competitor, ModelStub model) throws Exception {
        SessionViewDTO before = getSession(port, competitor);
        assertThat(history(port, competitor)).isEmpty();
        int requestCount = model.requestCount();
        HttpResponse<String> response = submitUser(port, competitor, "capacity probe");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("RUNTIME_CAPACITY_EXCEEDED");
        assertThat(getSession(port, competitor)).isEqualTo(before);
        assertThat(history(port, competitor)).isEmpty();
        assertThat(model.requestCount()).isEqualTo(requestCount);
    }

    private static void assertReleasedResources(int port, String sessionId, String competitor, ModelStub model)
            throws Exception {
        assertUserStream(submitUser(port, competitor, "capacity probe"), "capacity probe", "process-level answer 4");
        assertThat(history(port, competitor)).hasSize(2);
        assertUserStream(
                submitUser(port, sessionId, "same JVM continuation"),
                "same JVM continuation",
                "process-level answer 5");
        assertThat(model.lastRequest().path("messages").toString())
                .contains(SUMMARY, KEPT_TEXT)
                .doesNotContain(OLD_TEXT);
        assertThat(getSession(port, sessionId).result().path("state").asText()).isEqualTo("idle");
        assertThat(model.requestCount()).isEqualTo(5);
    }

    private static void awaitCompaction(int port, String sessionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode entries = history(port, sessionId);
            if (entries.size() == 5
                    && getSession(port, sessionId)
                            .result()
                            .path("state")
                            .asText()
                            .equals("idle")) {
                JsonNode compacted = entries.get(4);
                assertThat(compacted.path("type").asText()).isEqualTo("session.compaction.completed");
                assertThat(compacted.path("summary").asText()).isEqualTo(SUMMARY);
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Accepted compaction did not reach its persisted idle terminal state");
    }

    private static void assertRestartedInput(
            ProcessTestConfigDTO config,
            Path directory,
            int modelPort,
            String sessionId,
            SessionViewDTO savedSession,
            JsonNode savedHistory,
            ModelStub model)
            throws Exception {
        int port = freePort();
        RuntimeProcess restarted = startRuntime(config, directory, port, modelPort);
        try (restarted) {
            awaitHealth(restarted, port);
            assertThat(getSession(port, sessionId)).isEqualTo(savedSession);
            assertThat(history(port, sessionId)).isEqualTo(savedHistory);
            assertUserStream(submitUser(port, sessionId, NEXT_TEXT), NEXT_TEXT, "process-level answer 6");
            String messages = model.lastRequest().path("messages").toString();
            assertThat(messages)
                    .contains(SUMMARY, KEPT_TEXT, "same JVM continuation", NEXT_TEXT)
                    .doesNotContain(OLD_TEXT);
            assertThat(getSession(port, sessionId).result().path("state").asText())
                    .isEqualTo("idle");
            assertThat(history(port, sessionId)).hasSize(9);
            assertThat(model.requestCount()).isEqualTo(6);
        }
        assertThat(restarted.process().isAlive()).isFalse();
    }

    private static Socket openCompactSocket(int port, String sessionId) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            byte[] body = "{\"name\":\"compact\"}".getBytes(StandardCharsets.UTF_8);
            String headers = "POST " + sessionUri(port, sessionId, "command").getPath() + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\nContent-Type: application/json\r\n"
                    + "Accept-Language: zh-CN\r\nContent-Length: " + body.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            return socket;
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    private static HttpRequest compactRequest(int port, String sessionId) {
        return HttpRequest.newBuilder(sessionUri(port, sessionId, "command"))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "zh-CN")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"compact\"}", StandardCharsets.UTF_8))
                .build();
    }

    private static void assertCompactJson(HttpResponse<String> response, boolean compacted) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Language")).contains("zh-CN");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(MAPPER.readTree(response.body()))
                .isEqualTo(MAPPER.valueToTree(
                        Map.of("resCode", "0", "resMsg", "success", "result", Map.of("compacted", compacted))));
    }

    private static HttpResponse<String> submitUser(int port, String sessionId, String message) throws Exception {
        return send(HttpRequest.newBuilder(eventsUri(port, sessionId, null))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(Map.of("message", message, "fileIds", List.of())),
                        StandardCharsets.UTF_8))
                .build());
    }

    private static void assertUserStream(HttpResponse<String> response, String message, String answer) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.body()).contains(message, answer).doesNotContain("event:stream.error");
    }

    private static JsonNode history(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, "limit=20"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("result").path("events");
    }

    private static void assertCompactionStorage(ProcessTestConfigDTO config, String sessionId, String retainedId)
            throws Exception {
        var entries = query(
                config,
                "SELECT * FROM campusclaw_session.t_session_entries WHERE session_id=? ORDER BY entry_seq",
                sessionId);
        assertThat(entries)
                .extracting(row -> row.get("type"))
                .containsExactly(
                        "user.message",
                        "assistant.message.completed",
                        "user.message",
                        "assistant.message.completed",
                        "session.compaction.completed");
        var entry = entries.getLast();
        JsonNode payload = MAPPER.readTree(entry.get("payload"));
        assertThat(payload.path("summary").asText()).isEqualTo(SUMMARY);
        assertThat(payload.path("firstKeptEntryId").asText()).isEqualTo(retainedId);
        assertThat(payload.path("reason").asText()).isEqualTo("manual");
        assertThat(payload.path("willRetry").asBoolean()).isFalse();
        assertThat(payload.path("tokensBefore").asInt()).isPositive();
        assertThat(payload.path("estimatedTokensAfter").asInt()).isPositive();
        var records = query(
                config,
                "SELECT * FROM campusclaw_session.t_session_records WHERE session_id=? ORDER BY record_seq",
                sessionId);
        assertThat(records).hasSize(3);
        var usage = records.getLast();
        JsonNode usagePayload = MAPPER.readTree(usage.get("payload"));
        assertThat(usagePayload.path("cause").asText()).isEqualTo("compaction");
        assertThat(usagePayload.path("entryId").asText()).isEqualTo(entry.get("id"));
        assertThat(usagePayload.path("usage").path("totalTokens").asInt()).isEqualTo(7);
        assertThat(Long.parseLong(usage.get("record_seq"))).isEqualTo(Long.parseLong(entry.get("entry_seq")) + 1L);
        assertThat(usage.get("run_id")).isNotBlank().isNotEqualTo(entry.get("id"));
        var stats = query(config, "SELECT * FROM campusclaw_session.t_session_stats WHERE session_id=?", sessionId);
        assertThat(stats).hasSize(1);
        assertThat(stats.getFirst().get("total_tokens")).isEqualTo("21");
    }

    private static List<List<Map<String, String>>> persistentState(ProcessTestConfigDTO config, String sessionId)
            throws Exception {
        List<List<Map<String, String>>> state = new ArrayList<>();
        state.add(query(config, "SELECT * FROM campusclaw_session.t_sessions WHERE id=?", sessionId));
        for (String table : List.of(
                "t_session_entries",
                "t_session_records",
                "t_session_sequences",
                "t_session_stats",
                "t_session_materialized",
                "t_session_events",
                "t_session_executions",
                "t_session_execution_segments",
                "t_session_execution_segment_events")) {
            state.add(query(config, "SELECT * FROM campusclaw_session." + table + " WHERE session_id=?", sessionId));
        }
        return state;
    }

    private static List<Map<String, String>> query(ProcessTestConfigDTO config, String sql, String sessionId)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                        config.databaseUrl(), config.databaseUser(), config.databasePassword());
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                List<Map<String, String>> result = new ArrayList<>();
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(metadata.getColumnName(column), rows.getString(column));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }
}
