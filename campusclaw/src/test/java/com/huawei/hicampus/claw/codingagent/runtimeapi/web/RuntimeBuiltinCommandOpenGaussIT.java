/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.AGENT_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MODEL_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SECOND_MODEL_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.awaitHealth;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.createSession;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.eventsUri;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.freePort;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.getSession;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.loadConfiguration;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.prepareRuntimeFiles;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.requireSuccessfulStream;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.send;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.sessionUri;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.startRuntime;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.submitUserEventAsync;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SessionViewDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 通过真实 JAR、HTTP 和独立数据库验证 Builtin 命令及跨 JVM 配置恢复。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeBuiltinCommandOpenGaussIT {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "zh-CN"})
    void shouldQuerySevenBuiltinsWithoutChangingEmptySession(String language) throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int port = freePort();
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model;
                var runtime = startRuntime(config, tempDir, port, modelPort)) {
            model.start();
            awaitHealth(runtime, port);
            String sessionId = createSession(port);
            SessionViewDTO initial = getSession(port, sessionId);
            JsonNode stored = databaseSnapshot(config, sessionId);
            assertThat(initial.result().has("displayName")).isTrue();
            assertThat(initial.result().path("displayName").isNull()).isTrue();
            assertReadonlyResults(port, sessionId, language, initial);
            assertThat(result(command(port, sessionId, "compact", null, language)))
                    .isEqualTo(MAPPER.readTree("{\"compacted\":false}"));
            assertThat(getSession(port, sessionId)).isEqualTo(initial);
            assertThat(history(port, sessionId)).isEmpty();
            assertThat(databaseSnapshot(config, sessionId)).isEqualTo(stored);
            assertThat(model.requestCount()).isZero();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldPersistChangesAndNoOpsAcrossNewJvm() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        SessionViewDTO saved;
        JsonNode savedHistory;
        String sessionId;
        int firstPort = freePort();
        try (model) {
            model.start();
            var first = startRuntime(config, tempDir, firstPort, modelPort);
            try (first) {
                awaitHealth(first, firstPort);
                sessionId = createSession(firstPort);
                saved = assertChangesAndNoOps(config, firstPort, sessionId);
                savedHistory = history(firstPort, sessionId);
                assertTypes(savedHistory, "session.thinking_changed", "session.model_changed");
                assertDatabaseTypes(config, sessionId, "session.thinking.changed", "session.model.changed");
                assertThat(model.requestCount()).isZero();
            }
            assertThat(first.process().isAlive()).isFalse();
            int secondPort = freePort();
            var second = startRuntime(config, tempDir, secondPort, modelPort);
            try (second) {
                awaitHealth(second, secondPort);
                assertThat(getSession(secondPort, sessionId)).isEqualTo(saved);
                assertThat(history(secondPort, sessionId)).isEqualTo(savedHistory);
                assertSessionResult(command(secondPort, sessionId, "name", null, "en-US"), saved);
                String stream = requireSuccessfulStream(submitUserEventAsync(secondPort, sessionId));
                assertThat(stream)
                        .contains("\"type\":\"user.message\"", "\"type\":\"agent.message\"")
                        .doesNotContain("event:");
                assertThat(model.lastRequest().path("model").asText()).isEqualTo(SECOND_MODEL_ID);
                assertThat(model.lastRequest().path("messages").toString()).contains("process smoke");
                assertThat(getSession(secondPort, sessionId)
                                .result()
                                .path("state")
                                .asText())
                        .isEqualTo("idle");
            }
            assertThat(second.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldAllowRunningQueriesAndNameButRejectConfigurationWrites() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int port = freePort();
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model;
                var runtime = startRuntime(config, tempDir, port, modelPort)) {
            model.start();
            awaitHealth(runtime, port);
            String sessionId = createSession(port);
            var gate = model.blockNextResponse();
            var stream = submitUserEventAsync(port, sessionId);
            try {
                gate.awaitRequest();
                SessionViewDTO running = getSession(port, sessionId);
                assertThat(running.result().path("state").asText()).isEqualTo("running");
                assertReadonlyResults(port, sessionId, "en-US", running);
                assertError(command(port, sessionId, "model", MODEL_ID, "en-US"), 409, "SESSION_BUSY");
                assertError(command(port, sessionId, "thinking", "on", "en-US"), 409, "SESSION_BUSY");
                assertError(command(port, sessionId, "compact", null, "en-US"), 409, "SESSION_BUSY");
                HttpResponse<String> renamed = command(port, sessionId, "name", "运行中  名称", "en-US");
                assertThat(result(renamed).path("state").asText()).isEqualTo("running");
                assertThat(result(renamed).path("displayName").asText()).isEqualTo("运行中  名称");
                assertSessionResult(renamed, getSession(port, sessionId));
                assertTypes(history(port, sessionId), "user.message");
                assertThat(model.requestCount()).isEqualTo(1);
            } finally {
                gate.release();
            }
            assertThat(requireSuccessfulStream(stream))
                    .contains("\"type\":\"session.status_idle\"")
                    .doesNotContain("event:", "stream.error");
            assertTypes(history(port, sessionId), "user.message", "agent.message", "session.status_idle");
            assertThat(getSession(port, sessionId).result().path("displayName").asText())
                    .isEqualTo("运行中  名称");
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldRejectUnsafeNamesAndInvalidCommandsWithoutSideEffects() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int port = freePort();
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model;
                var runtime = startRuntime(config, tempDir, port, modelPort)) {
            model.start();
            awaitHealth(runtime, port);
            String sessionId = createSession(port);
            String boundary = "😀".repeat(20);
            assertThat(boundary.getBytes(StandardCharsets.UTF_8)).hasSize(80);
            assertThat(result(command(port, sessionId, "name", boundary, "en-US"))
                            .path("displayName")
                            .asText())
                    .isEqualTo(boundary);
            SessionViewDTO saved = getSession(port, sessionId);
            JsonNode stored = databaseSnapshot(config, sessionId);
            for (String invalid :
                    List.of(" ", boundary + "a", "a\nb", "a\rb", "a\u0001b", "a\u0085b", "a\u202Eb", "a\u2066b")) {
                assertError(command(port, sessionId, "name", invalid, "en-US"), 400, "INVALID_COMMAND_REQUEST");
            }
            assertInvalidArguments(port, sessionId);
            assertError(command(port, sessionId, "unknown", null, "en-US"), 404, "COMMAND_NOT_FOUND");
            assertError(command(port, sessionId, "model", "unbound-model", "en-US"), 422, "MODEL_NOT_AVAILABLE");
            assertError(
                    command(port, "session-00000000000000000000000000000000", "status", null, "en-US"),
                    404,
                    "SESSION_NOT_FOUND");
            assertThat(getSession(port, sessionId)).isEqualTo(saved);
            assertThat(history(port, sessionId)).isEmpty();
            assertThat(databaseSnapshot(config, sessionId)).isEqualTo(stored);
            assertThat(model.requestCount()).isZero();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldDistinguishEmptyBindingsFromMissingCompleteSnapshot() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        Path managed = tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw");
        Files.move(managed.resolve("skills/process-analysis"), tempDir.resolve("unbound-skill"));
        int port = freePort();
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model;
                var runtime = startRuntime(config, tempDir, port, modelPort)) {
            model.start();
            awaitHealth(runtime, port);
            String sessionId = createSession(port);
            SessionViewDTO saved = getSession(port, sessionId);
            assertThat(result(command(port, sessionId, "skills", null, "en-US")))
                    .isEqualTo(MAPPER.readTree("{\"skills\":[]}"));
            Path manifest = managed.resolve("agent.json");
            Path backup = tempDir.resolve("agent-backup.json");
            Files.move(manifest, backup);
            try {
                for (String name : List.of("help", "skills")) {
                    assertError(command(port, sessionId, name, null, "en-US"), 422, "AGENT_NOT_AVAILABLE");
                }
                HttpResponse<String> catalog = send(HttpRequest.newBuilder(sessionUri(port, sessionId, "commands"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build());
                assertError(catalog, 503, "AGENT_NOT_AVAILABLE");
                assertThat(catalog.headers().firstValue("Retry-After")).contains("3");
                assertSessionResult(command(port, sessionId, "status", null, "en-US"), saved);
            } finally {
                Files.move(backup, manifest);
            }
            assertThat(getSession(port, sessionId)).isEqualTo(saved);
            assertThat(history(port, sessionId)).isEmpty();
            assertThat(model.requestCount()).isZero();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldTurnThinkingOffWhenDeploymentModelCapabilityChanges() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        String sessionId;
        int firstPort = freePort();
        try (model) {
            model.start();
            var first = startRuntime(config, tempDir, firstPort, modelPort);
            try (first) {
                awaitHealth(first, firstPort);
                sessionId = createSession(firstPort);
                assertThat(getSession(firstPort, sessionId)
                                .result()
                                .path("thinking")
                                .asBoolean())
                        .isTrue();
            }
            assertThat(first.process().isAlive()).isFalse();
            int secondPort = freePort();
            var second = startRuntime(config, tempDir, secondPort, modelPort, "--campusmate.model.reasoning=false");
            try (second) {
                awaitHealth(second, secondPort);
                JsonNode changed = result(command(secondPort, sessionId, "model", SECOND_MODEL_ID, "en-US"));
                assertThat(changed.path("modelId").asText()).isEqualTo(SECOND_MODEL_ID);
                assertThat(changed.path("thinking").asBoolean()).isFalse();
                assertTypes(history(secondPort, sessionId), "session.model_changed", "session.thinking_changed");
                SessionViewDTO saved = getSession(secondPort, sessionId);
                assertError(command(secondPort, sessionId, "thinking", "on", "en-US"), 422, "THINKING_NOT_SUPPORTED");
                assertSessionResult(command(secondPort, sessionId, "thinking", "off", "en-US"), saved);
                assertThat(getSession(secondPort, sessionId)).isEqualTo(saved);
                assertTypes(history(secondPort, sessionId), "session.model_changed", "session.thinking_changed");
                assertThat(model.requestCount()).isZero();
            }
            assertThat(second.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldRejectInvalidWireRequestsAndHeaderPresence() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int port = freePort();
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model;
                var runtime = startRuntime(config, tempDir, port, modelPort)) {
            model.start();
            awaitHealth(runtime, port);
            String sessionId = createSession(port);
            JsonNode stored = databaseSnapshot(config, sessionId);
            for (String body : List.of(
                    "",
                    "null",
                    "{}",
                    "{\"name\":3}",
                    "{\"name\":\"/help\"}",
                    "{\"name\":\"help\",\"fileIds\":null}",
                    "{\"name\":\"help\",\"arguments\":true}",
                    "{\"name\":\"help\",\"extra\":0}",
                    MAPPER.writeValueAsString(Map.of("name", "help", "arguments", "x".repeat(262145))))) {
                assertError(
                        send(commandRequest(port, sessionId, body, "en-US").build()), 400, "INVALID_COMMAND_REQUEST");
            }
            for (String header : List.of("If-Match", "Idempotency-Key")) {
                for (String value : List.of("", "unsupported")) {
                    assertError(
                            send(commandRequest(port, sessionId, "{\"name\":\"help\"}", "en-US")
                                    .header(header, value)
                                    .build()),
                            400,
                            "INVALID_COMMAND_REQUEST");
                }
            }
            HttpResponse<String> help = send(commandRequest(port, sessionId, "{\"name\":\"help\"}", "en-US")
                    .setHeader("Accept", "text/event-stream")
                    .build());
            assertThat(result(help).path("displayName").asText()).isEqualTo("跨进程测试助手");
            assertThat(help.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
            assertThat(databaseSnapshot(config, sessionId)).isEqualTo(stored);
            assertThat(model.requestCount()).isZero();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    private static void assertReadonlyResults(int port, String sessionId, String language, SessionViewDTO expected)
            throws Exception {
        for (String name : List.of("status", "name", "thinking")) {
            assertSessionResult(command(port, sessionId, name, null, language), expected);
        }
        assertThat(result(command(port, sessionId, "help", null, language)))
                .isEqualTo(
                        MAPPER.readTree(
                                """
                        {"displayName":"跨进程测试助手","description":["分析订单并生成摘要"],"userCases":["请分析本次订单"]}
                        """));
        assertThat(result(command(port, sessionId, "model", null, language)))
                .isEqualTo(MAPPER.valueToTree(
                        Map.of("currentModelId", MODEL_ID, "models", List.of(MODEL_ID, SECOND_MODEL_ID))));
        assertThat(result(command(port, sessionId, "skills", null, language)))
                .isEqualTo(
                        MAPPER.readTree("{\"skills\":[{\"name\":\"process-analysis\",\"description\":\"分析测试订单\"}]}"));
    }

    private static SessionViewDTO assertChangesAndNoOps(ProcessTestConfigDTO config, int port, String sessionId)
            throws Exception {
        SessionViewDTO initial = getSession(port, sessionId);
        HttpResponse<String> name = command(port, sessionId, "name", "  订单  分析  ", "en-US");
        assertThat(result(name).path("displayName").asText()).isEqualTo("订单  分析");
        assertThat(name.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(initial.etag());
        SessionViewDTO named = getSession(port, sessionId);
        assertSessionResult(name, named);
        assertNoOp(config, port, sessionId, "name", "  订单  分析  ", named);
        assertThat(history(port, sessionId)).isEmpty();
        HttpResponse<String> thinking = command(port, sessionId, "thinking", "off", "en-US");
        assertThat(result(thinking).path("thinking").asBoolean()).isFalse();
        SessionViewDTO disabled = getSession(port, sessionId);
        assertSessionResult(thinking, disabled);
        assertThat(disabled.etag()).isNotEqualTo(named.etag());
        assertNoOp(config, port, sessionId, "thinking", "off", disabled);
        HttpResponse<String> changed = command(port, sessionId, "model", SECOND_MODEL_ID, "en-US");
        assertThat(result(changed).path("modelId").asText()).isEqualTo(SECOND_MODEL_ID);
        SessionViewDTO saved = getSession(port, sessionId);
        assertSessionResult(changed, saved);
        assertThat(saved.etag()).isNotEqualTo(disabled.etag());
        assertNoOp(config, port, sessionId, "model", SECOND_MODEL_ID, saved);
        assertStalePutRejected(port, sessionId, "model", named.etag(), "{\"modelId\":\"" + MODEL_ID + "\"}");
        assertStalePutRejected(port, sessionId, "thinking", named.etag(), "{\"thinking\":true}");
        assertThat(getSession(port, sessionId)).isEqualTo(saved);
        return saved;
    }

    private static void assertNoOp(
            ProcessTestConfigDTO config,
            int port,
            String sessionId,
            String name,
            String arguments,
            SessionViewDTO expected)
            throws Exception {
        JsonNode stored = databaseSnapshot(config, sessionId);
        assertSessionResult(command(port, sessionId, name, arguments, "en-US"), expected);
        assertThat(databaseSnapshot(config, sessionId)).isEqualTo(stored);
    }

    private static JsonNode databaseSnapshot(ProcessTestConfigDTO config, String sessionId) throws Exception {
        ObjectNode snapshot = MAPPER.createObjectNode();
        try (var connection =
                DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword())) {
            for (String table : List.of(
                    "t_sessions",
                    "t_session_entries",
                    "t_session_sequences",
                    "t_session_stats",
                    "t_session_materialized",
                    "t_session_records")) {
                String key = table.equals("t_sessions") ? "id" : "session_id";
                try (var statement = connection.prepareStatement(
                        "SELECT * FROM campusclaw_session." + table + " WHERE " + key + " = ?")) {
                    statement.setString(1, sessionId);
                    try (var rows = statement.executeQuery()) {
                        List<String> contents = new ArrayList<>();
                        while (rows.next()) {
                            ObjectNode row = MAPPER.createObjectNode();
                            for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++) {
                                row.put(rows.getMetaData().getColumnLabel(index), rows.getString(index));
                            }
                            contents.add(row.toString());
                        }
                        snapshot.set(
                                table,
                                MAPPER.valueToTree(contents.stream().sorted().toList()));
                    }
                }
            }
        }
        return snapshot;
    }

    private static void assertDatabaseTypes(ProcessTestConfigDTO config, String sessionId, String... expected)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                        config.databaseUrl(), config.databaseUser(), config.databasePassword());
                var statement = connection.prepareStatement(
                        "SELECT type FROM campusclaw_session.t_session_entries WHERE session_id = ? ORDER BY entry_seq")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                List<String> types = new ArrayList<>();
                while (rows.next()) {
                    types.add(rows.getString(1));
                }
                assertThat(types).containsExactly(expected);
            }
        }
    }

    private static void assertInvalidArguments(int port, String sessionId) throws Exception {
        for (String name : List.of("help", "status", "skills", "compact")) {
            assertError(command(port, sessionId, name, "unexpected", "en-US"), 400, "INVALID_COMMAND_REQUEST");
        }
        for (String arguments : List.of("ON", "true", "false", " on")) {
            assertError(command(port, sessionId, "thinking", arguments, "en-US"), 400, "INVALID_COMMAND_REQUEST");
        }
    }

    private static void assertStalePutRejected(int port, String sessionId, String resource, String etag, String body)
            throws Exception {
        var response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, resource))
                .timeout(Duration.ofSeconds(5))
                .header("If-Match", etag)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        assertError(response, 412, "SESSION_VERSION_MISMATCH");
    }

    private static HttpResponse<String> command(
            int port, String sessionId, String name, String arguments, String language) throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("name", name);
        body.put("arguments", arguments);
        var response =
                send(commandRequest(port, sessionId, body.toString(), language).build());
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value ->
                        assertThat(value).startsWith("application/json").doesNotContain("text/event-stream"));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Language")).contains(language);
        if (response.statusCode() == 200 && List.of("help", "skills", "compact").contains(name)) {
            assertThat(response.headers().firstValue("ETag")).isEmpty();
        }
        if (response.statusCode() == 200 && name.equals("model") && arguments == null) {
            assertThat(response.headers().firstValue("ETag")).isEmpty();
        }
        return response;
    }

    private static HttpRequest.Builder commandRequest(int port, String sessionId, String body, String language) {
        return HttpRequest.newBuilder(sessionUri(port, sessionId, "command"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Accept-Language", language)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private static JsonNode result(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.properties()).extracting(Map.Entry::getKey).containsExactly("resCode", "resMsg", "result");
        assertThat(envelope.path("resCode").asText()).isEqualTo("0");
        return envelope.path("result");
    }

    private static void assertSessionResult(HttpResponse<String> response, SessionViewDTO expected) throws Exception {
        assertThat(result(response)).isEqualTo(expected.result());
        assertThat(response.headers().firstValue("ETag")).contains(expected.etag());
        assertThat(result(response).properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "sessionId",
                        "agentId",
                        "displayName",
                        "modelId",
                        "state",
                        "thinking",
                        "lifetimeUsage",
                        "createdAt",
                        "updatedAt");
    }

    private static void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.properties()).extracting(Map.Entry::getKey).containsExactly("resCode", "resMsg");
        assertThat(envelope.path("resCode").asText()).isEqualTo(code);
        assertThat(envelope.path("resMsg").asText()).isNotBlank();
    }

    private static JsonNode history(int port, String sessionId) throws Exception {
        var response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, "limit=100"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
        JsonNode page = result(response);
        assertThat(page.path("nextPage").isNull()).isTrue();
        return page.path("events");
    }

    private static void assertTypes(JsonNode events, String... expected) {
        assertThat(events).extracting(event -> event.path("type").asText()).containsExactly(expected);
    }
}
