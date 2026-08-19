/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 打包 JAR、真实 HTTP/SSE、模型协议桩与 openGauss 的跨进程集成测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeHttpProcessOpenGaussIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AGENT_ID = "agent_011CZkYqphY8vELVzwCUpqiQ";

    private static final String MODEL_ID = "runtime-smoke-model";

    private static final String SECOND_MODEL_ID = "runtime-smoke-model-2";

    private static final String CALLER_ID = "mate-service";

    private static final String JWT = "process-jwt";

    private static final String APP_KEY = "process-appkey";

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void packagedJarStreamsAndPagesPersistedEvents(@TempDir Path tempDir) throws Exception {
        ProcessTestConfig config = ProcessTestConfig.load();
        Assumptions.assumeTrue(config.available(), config.skipReason());
        int applicationPort = freePort();
        int modelPort = freePort();
        prepareRuntimeFiles(tempDir, modelPort);
        truncateRuntimeTables(config);

        try (ModelStub modelStub = new ModelStub(modelPort);
                RuntimeProcess runtime = startRuntime(config, tempDir, applicationPort)) {
            modelStub.start();
            awaitHealth(runtime, applicationPort);
            String sessionId = createSession(applicationPort);
            ModelGate controlGate = modelStub.blockNextResponse();
            CompletableFuture<HttpResponse<String>> streamResponse = submitUserEventAsync(applicationPort, sessionId);
            controlGate.awaitRequest();
            assertControlAccepted(applicationPort, sessionId, "steers", "先只分析异常订单");
            assertControlAccepted(applicationPort, sessionId, "follow-ups", "完成后再给出摘要");
            controlGate.release();
            String stream = requireSuccessfulStream(streamResponse);
            assertStreamOrder(stream);
            assertIdleControlRejected(applicationPort, sessionId);
            assertHistoryPagination(applicationPort, sessionId);
            assertSessionConfiguration(applicationPort, sessionId);
            assertDatabaseState(config, sessionId);
            assertDelete(applicationPort, config, sessionId);
            assertAbort(applicationPort, modelStub);
            assertThat(modelStub.requestPath()).isEqualTo("/v1/chat/completions");
        }
    }

    private static RuntimeProcess startRuntime(ProcessTestConfig config, Path tempDir, int port) throws IOException {
        Path log = tempDir.resolve("runtime-process.log");
        ProcessBuilder builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        config.jar().toString(),
                        "--server.address=127.0.0.1",
                        "--server.port=" + port)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        configureEnvironment(builder, config, tempDir);
        return new RuntimeProcess(builder.start(), log);
    }

    private static void configureEnvironment(ProcessBuilder builder, ProcessTestConfig config, Path tempDir) {
        var environment = builder.environment();
        environment.put("CAMPUSCLAW_HOME", tempDir.resolve("home").toString());
        environment.put("GAUSSDB_URL", config.databaseUrl());
        environment.put("GAUSSDB_USER", config.databaseUser());
        environment.put("GAUSSDB_PASSWORD", config.databasePassword());
        environment.put("GAUSSDB_SCHEMA", "campusclaw_session");
        environment.put("GAUSSDB_SSL_MODE", "disable");
        environment.put("SPRING_APPLICATION_JSON", springConfiguration(tempDir));
    }

    private static String springConfiguration(Path tempDir) {
        ObjectNode runtime = MAPPER.createObjectNode();
        runtime.putObject("agent-directory")
                .put("root", tempDir.resolve("agent").toString());
        runtime.putObject("events").put("cursor-secret", "process-cursor-secret-at-least-32-bytes");
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("campusclaw").set("runtime", runtime);
        return root.toString();
    }

    private static void prepareRuntimeFiles(Path tempDir, int modelPort) throws IOException {
        Path agentHome = tempDir.resolve("home/agent");
        Path managedDirectory = tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw");
        Files.createDirectories(agentHome);
        Files.createDirectories(managedDirectory);
        Files.writeString(agentHome.resolve("settings.json"), globalSettings(modelPort), StandardCharsets.UTF_8);
        Files.writeString(managedDirectory.resolve("settings.json"), agentSettings(), StandardCharsets.UTF_8);
        Files.writeString(
                managedDirectory.resolve("SYSTEM.md"), "Deterministic process test agent.", StandardCharsets.UTF_8);
    }

    private static String globalSettings(int modelPort) {
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("model", MODEL_ID);
        settings.putArray("customModels")
                .add(modelDefinition(MODEL_ID, "Runtime Smoke Model", modelPort, true))
                .add(modelDefinition(SECOND_MODEL_ID, "Runtime Smoke Model 2", modelPort, false));
        return settings.toString();
    }

    private static ObjectNode modelDefinition(String id, String name, int modelPort, boolean reasoning) {
        ObjectNode model = MAPPER.createObjectNode();
        model.put("id", id);
        model.put("name", name);
        model.put("api", "openai-completions");
        model.put("baseUrl", "http://127.0.0.1:" + modelPort + "/v1");
        model.put("apiKey", "process-smoke-key");
        model.put("contextWindow", 128_000);
        model.put("maxTokens", 4_096);
        model.put("reasoning", reasoning);
        model.putArray("inputModalities").add("text");
        return model;
    }

    private static String agentSettings() {
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("defaultModel", MODEL_ID);
        settings.putArray("enabledModels").add(MODEL_ID).add(SECOND_MODEL_ID);
        return settings.toString();
    }

    private static void awaitHealth(RuntimeProcess runtime, int port) throws Exception {
        URI probe = URI.create("http://127.0.0.1:" + port + "/campusclaw-service/v1/sessions/readiness-probe");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!runtime.process().isAlive()) {
                throw new AssertionError("Runtime exited before health check:\n" + runtime.logContent());
            }
            try {
                HttpResponse<String> response = CLIENT.send(
                        HttpRequest.newBuilder(probe)
                                .header("X-HW-ID", CALLER_ID)
                                .header("Authorization", "Bearer " + JWT)
                                .timeout(Duration.ofSeconds(1))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return;
                }
            } catch (IOException ignored) {
                Thread.sleep(100L);
            }
        }
        throw new AssertionError("Runtime readiness probe timed out:\n" + runtime.logContent());
    }

    private static String createSession(int port) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/campusclaw-service/v1/agents/" + AGENT_ID + "/sessions");
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertThat(response.statusCode()).isEqualTo(201);
        return MAPPER.readTree(response.body())
                .path("result")
                .path("session_id")
                .asText();
    }

    private static CompletableFuture<HttpResponse<String>> submitUserEventAsync(int port, String sessionId) {
        URI uri = eventsUri(port, sessionId, null);
        String body = "{\"type\":\"user.message\",\"message\":\"process smoke\",\"file_ids\":[]}";
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String requireSuccessfulStream(CompletableFuture<HttpResponse<String>> future) throws Exception {
        HttpResponse<String> response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        return response.body();
    }

    private static void assertControlAccepted(int port, String sessionId, String resource, String message)
            throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, resource))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"" + message + "\"}"))
                .build());
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode result = MAPPER.readTree(response.body()).path("result");
        assertThat(result.path("session_id").asText()).isEqualTo(sessionId);
        assertThat(result.path("accepted_at").asText()).isNotBlank();
    }

    private static void assertIdleControlRejected(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, "steers"))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"too late\"}"))
                .build());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("SESSION_NOT_RUNNING");
    }

    private static void assertAbort(int port, ModelStub modelStub) throws Exception {
        String sessionId = createSession(port);
        ModelGate gate = modelStub.blockNextResponse();
        CompletableFuture<HttpResponse<String>> streamResponse = submitUserEventAsync(port, sessionId);
        gate.awaitRequest();
        assertRunningDeleteRejected(port, sessionId);
        HttpRequest request = HttpRequest.newBuilder(sessionUri(port, sessionId, "abort"))
                .header("X-HW-ID", CALLER_ID)
                .header("X-HW-APPKEY", APP_KEY)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response;
        try {
            response = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(10, TimeUnit.SECONDS);
        } finally {
            gate.release();
        }
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        String stream = requireSuccessfulStream(streamResponse);
        assertThat(stream).contains("event:session.status.idle", "event:stream.end", "\"reason\":\"aborted\"");
        assertThat(getSession(port, sessionId).result().path("state").asText()).isEqualTo("idle");
        assertThat(send(request).statusCode()).isEqualTo(204);
    }

    private static void assertRunningDeleteRejected(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .DELETE()
                .build());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("SESSION_BUSY");
    }

    private static void assertDelete(int port, ProcessTestConfig config, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("X-HW-APPKEY", APP_KEY)
                .DELETE()
                .build();
        HttpResponse<String> response = send(request);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(send(request).statusCode()).isEqualTo(204);
        assertDeletedSessionIsHidden(port, sessionId);
        assertTombstone(config, sessionId);
    }

    private static void assertDeletedSessionIsHidden(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    private static void assertTombstone(ProcessTestConfig config, String sessionId) throws Exception {
        try (var connection = DriverManager.getConnection(
                        config.databaseUrl(), config.databaseUser(), config.databasePassword());
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM campusclaw_session.t_session_tombstone WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isOne();
            }
        }
    }

    private static void assertStreamOrder(String stream) {
        List<String> events = List.of(
                "user.message",
                "assistant.message.started",
                "assistant.message.delta",
                "assistant.message.completed",
                "session.status.idle",
                "stream.end");
        int previous = -1;
        for (String event : events) {
            int current = stream.indexOf("event:" + event);
            assertThat(current).as(event).isGreaterThan(previous);
            previous = current;
        }
        assertThat(stream).contains("process-level answer").doesNotContain("event:stream.error");
        assertThat(stream).contains("先只分析异常订单", "完成后再给出摘要");
        assertThat(stream.indexOf("先只分析异常订单")).isLessThan(stream.indexOf("完成后再给出摘要"));
        assertThat(countOccurrences(stream, "event:user.message")).isEqualTo(3);
        assertThat(countOccurrences(stream, "event:assistant.message.completed"))
                .isEqualTo(3);
    }

    private static int countOccurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static void assertHistoryPagination(int port, String sessionId) throws Exception {
        List<String> expectedTypes = List.of(
                "user.message",
                "assistant.message.completed",
                "user.message",
                "assistant.message.completed",
                "user.message",
                "assistant.message.completed");
        String cursor = null;
        for (int index = 0; index < expectedTypes.size(); index++) {
            String query = cursor == null ? "limit=1" : "limit=1&page=" + cursor;
            JsonNode page = listEvents(port, sessionId, query);
            JsonNode event = page.path("events").get(0);
            assertThat(event.path("type").asText()).isEqualTo(expectedTypes.get(index));
            if (index == 1) {
                assertThat(event.path("message")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asText())
                        .isEqualTo("process-level answer");
            }
            cursor = page.path("next_page").isNull()
                    ? null
                    : page.path("next_page").asText();
            if (index < expectedTypes.size() - 1) {
                assertThat(cursor).startsWith("page_").doesNotContain(sessionId);
            }
        }
        assertThat(cursor).isNull();
    }

    private static void assertSessionConfiguration(int port, String sessionId) throws Exception {
        SessionView initial = getSession(port, sessionId);
        JsonNode models = listModels(port, sessionId);
        assertThat(models.path("current_model_id").asText()).isEqualTo(MODEL_ID);
        List<String> availableModels = MAPPER.readerForListOf(String.class).readValue(models.path("models"));
        assertThat(availableModels).containsExactly(MODEL_ID, SECOND_MODEL_ID);

        SessionView thinking = updateConfiguration(port, sessionId, "thinking", initial.etag(), "{\"thinking\":true}");
        assertThat(thinking.result().path("thinking").asBoolean()).isTrue();
        assertThat(thinking.etag()).isNotEqualTo(initial.etag());

        SessionView changed = updateConfiguration(
                port, sessionId, "model", thinking.etag(), "{\"model_id\":\"" + SECOND_MODEL_ID + "\"}");
        assertThat(changed.result().path("model_id").asText()).isEqualTo(SECOND_MODEL_ID);
        assertThat(changed.result().path("thinking").asBoolean()).isFalse();
        SessionView unchanged = updateConfiguration(
                port, sessionId, "model", changed.etag(), "{\"model_id\":\"" + SECOND_MODEL_ID + "\"}");
        assertThat(unchanged.etag()).isEqualTo(changed.etag());
        assertStaleConfigurationRejected(port, sessionId, thinking.etag());
    }

    private static SessionView getSession(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return sessionView(response);
    }

    private static JsonNode listModels(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, "models"))
                .header("X-HW-ID", CALLER_ID)
                .header("X-HW-APPKEY", APP_KEY)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("result");
    }

    private static SessionView updateConfiguration(
            int port, String sessionId, String resource, String etag, String body) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, resource))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("If-Match", etag)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return sessionView(response);
    }

    private static void assertStaleConfigurationRejected(int port, String sessionId, String etag) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, "thinking"))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("If-Match", etag)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"thinking\":false}"))
                .build());
        assertThat(response.statusCode()).isEqualTo(412);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("SESSION_VERSION_MISMATCH");
    }

    private static SessionView sessionView(HttpResponse<String> response) throws Exception {
        String etag = response.headers().firstValue("ETag").orElseThrow();
        return new SessionView(MAPPER.readTree(response.body()).path("result"), etag);
    }

    private static URI sessionUri(int port, String sessionId, String suffix) {
        String base = "http://127.0.0.1:" + port + "/campusclaw-service/v1/sessions/" + sessionId;
        return URI.create(suffix == null ? base : base + "/" + suffix);
    }

    private static JsonNode listEvents(int port, String sessionId, String query) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, query))
                .header("X-HW-ID", CALLER_ID)
                .header("X-HW-APPKEY", APP_KEY)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("result");
    }

    private static URI eventsUri(int port, String sessionId, String query) {
        String value = "http://127.0.0.1:" + port + "/campusclaw-service/v1/sessions/" + sessionId + "/events";
        return URI.create(query == null ? value : value + "?" + query);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void truncateRuntimeTables(ProcessTestConfig config) throws Exception {
        try (var connection = DriverManager.getConnection(
                        config.databaseUrl(), config.databaseUser(), config.databasePassword());
                var statement = connection.createStatement()) {
            statement.execute("SET search_path TO campusclaw_session");
            statement.execute("TRUNCATE TABLE t_session_materialized, t_session_sequences, t_session_entries, "
                    + "t_session_cleanup_task, t_session_tombstone, t_sessions");
        }
    }

    private static void assertDatabaseState(ProcessTestConfig config, String sessionId) throws Exception {
        try (var connection =
                DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword())) {
            try (var statement = connection.prepareStatement(
                    "SELECT state, resource_version, active_leaf_id FROM campusclaw_session.t_sessions WHERE id = ?")) {
                statement.setString(1, sessionId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("idle");
                    assertThat(rows.getLong(2)).isEqualTo(5L);
                    assertThat(rows.getString(3)).startsWith("entry_");
                }
            }
            assertEntryTypes(connection, sessionId);
        }
    }

    private static void assertEntryTypes(java.sql.Connection connection, String sessionId) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT type FROM campusclaw_session.t_session_entries WHERE session_id = ? ORDER BY entry_seq")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("user.message");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("assistant.message.completed");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("user.message");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("assistant.message.completed");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("user.message");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("assistant.message.completed");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record ProcessTestConfig(Path jar, String databaseUrl, String databaseUser, String databasePassword) {
        private static ProcessTestConfig load() {
            String jarValue = System.getProperty("runtime.it.jar", "");
            return new ProcessTestConfig(
                    jarValue.isBlank() ? null : Path.of(jarValue).toAbsolutePath(),
                    System.getProperty("gaussdb.it.url", ""),
                    System.getProperty("gaussdb.it.username", ""),
                    System.getProperty("gaussdb.it.password", ""));
        }

        private boolean available() {
            return jar != null
                    && Files.isRegularFile(jar)
                    && !databaseUrl.isBlank()
                    && !databaseUser.isBlank()
                    && !databasePassword.isBlank();
        }

        private String skipReason() {
            return "Set runtime.it.jar and gaussdb.it.url/username/password to run the process integration test";
        }
    }

    private record SessionView(JsonNode result, String etag) {}

    private static final class ModelGate {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        private void awaitRequest() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void release() {
            release.countDown();
        }

        private void blockResponse() throws InterruptedException {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class RuntimeProcess implements AutoCloseable {
        private final Process process;

        private final Path log;

        private RuntimeProcess(Process process, Path log) {
            this.process = process;
            this.log = log;
        }

        private Process process() {
            return process;
        }

        private String logContent() throws IOException {
            return Files.exists(log) ? Files.readString(log) : "";
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class ModelStub implements AutoCloseable {
        private final HttpServer server;

        private volatile String requestPath;

        private ModelGate pendingGate;

        private int responseCount;

        private ModelStub(int port) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::respond);
        }

        private void start() {
            server.start();
        }

        private String requestPath() {
            return requestPath;
        }

        private synchronized ModelGate blockNextResponse() {
            if (pendingGate != null) {
                throw new IllegalStateException("a model response is already blocked");
            }
            pendingGate = new ModelGate();
            return pendingGate;
        }

        private void respond(HttpExchange exchange) throws IOException {
            requestPath = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            awaitGate();
            byte[] response = response(++responseCount);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private void awaitGate() throws IOException {
            ModelGate gate;
            synchronized (this) {
                gate = pendingGate;
                pendingGate = null;
            }
            if (gate == null) {
                return;
            }
            try {
                gate.blockResponse();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("model response gate interrupted", error);
            }
        }

        private static byte[] response(int responseNumber) {
            String text = responseNumber == 1 ? "process-level answer" : "process-level answer " + responseNumber;
            return ("data: {\"id\":\"chatcmpl-process\",\"object\":\"chat.completion.chunk\","
                            + "\"created\":1786980000,\"model\":\"runtime-smoke-model\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"chatcmpl-process\",\"object\":\"chat.completion.chunk\","
                            + "\"created\":1786980000,\"model\":\"runtime-smoke-model\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"chatcmpl-process\",\"object\":\"chat.completion.chunk\","
                            + "\"created\":1786980000,\"model\":\"runtime-smoke-model\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,"
                            + "\"completion_tokens\":3,\"total_tokens\":7}}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            synchronized (this) {
                if (pendingGate != null) {
                    pendingGate.release();
                }
            }
            server.stop(0);
        }
    }
}
