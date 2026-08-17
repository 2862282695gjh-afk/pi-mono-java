/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

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
            String stream = submitUserEvent(applicationPort, sessionId);
            assertStreamOrder(stream);
            assertHistoryPagination(applicationPort, sessionId);
            assertDatabaseState(config, sessionId);
            assertThat(modelStub.requestPath()).isEqualTo("/v1/chat/completions");
        }
    }

    private static RuntimeProcess startRuntime(ProcessTestConfig config, Path tempDir, int port) throws IOException {
        Path log = tempDir.resolve("runtime-process.log");
        ProcessBuilder builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        config.jar().toString(),
                        "--mode",
                        "server",
                        "--host",
                        "127.0.0.1",
                        "--port",
                        Integer.toString(port))
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
        runtime.putObject("auth")
                .put("jwt-token", JWT)
                .put("app-key", APP_KEY)
                .putArray("allowed-callers")
                .add(CALLER_ID);
        runtime.putObject("template").put("root", tempDir.resolve("templates").toString());
        runtime.putObject("events").put("cursor-secret", "process-cursor-secret-at-least-32-bytes");
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("campusclaw").set("runtime", runtime);
        return root.toString();
    }

    private static void prepareRuntimeFiles(Path tempDir, int modelPort) throws IOException {
        Path agentHome = tempDir.resolve("home/agent");
        Path agentRoot = tempDir.resolve("templates").resolve(AGENT_ID);
        Path revisionRoot = agentRoot.resolve("revisions/rev-smoke/.campusagent");
        Files.createDirectories(agentHome);
        Files.createDirectories(revisionRoot);
        Files.writeString(agentHome.resolve("settings.json"), globalSettings(modelPort), StandardCharsets.UTF_8);
        Files.writeString(
                agentRoot.resolve("current.json"), "{\"bundleRevision\":\"rev-smoke\"}", StandardCharsets.UTF_8);
        Files.writeString(revisionRoot.resolve("settings.json"), agentSettings(), StandardCharsets.UTF_8);
        Files.writeString(
                revisionRoot.resolve("SYSTEM.md"), "Deterministic process test agent.", StandardCharsets.UTF_8);
    }

    private static String globalSettings(int modelPort) {
        ObjectNode model = MAPPER.createObjectNode();
        model.put("id", MODEL_ID);
        model.put("name", "Runtime Smoke Model");
        model.put("api", "openai-completions");
        model.put("baseUrl", "http://127.0.0.1:" + modelPort + "/v1");
        model.put("apiKey", "process-smoke-key");
        model.put("contextWindow", 128_000);
        model.put("maxTokens", 4_096);
        model.put("reasoning", false);
        model.putArray("inputModalities").add("text");
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("model", MODEL_ID);
        settings.putArray("customModels").add(model);
        return settings.toString();
    }

    private static String agentSettings() {
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("defaultModel", MODEL_ID);
        settings.putArray("enabledModels").add(MODEL_ID);
        return settings.toString();
    }

    private static void awaitHealth(RuntimeProcess runtime, int port) throws Exception {
        URI health = URI.create("http://127.0.0.1:" + port + "/api/health");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!runtime.process().isAlive()) {
                throw new AssertionError("Runtime exited before health check:\n" + runtime.logContent());
            }
            try {
                HttpResponse<String> response = CLIENT.send(
                        HttpRequest.newBuilder(health)
                                .timeout(Duration.ofSeconds(1))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                Thread.sleep(100L);
            }
        }
        throw new AssertionError("Runtime health check timed out:\n" + runtime.logContent());
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

    private static String submitUserEvent(int port, String sessionId) throws Exception {
        URI uri = eventsUri(port, sessionId, null);
        String body = "{\"type\":\"user.message\",\"message\":\"process smoke\",\"file_ids\":[]}";
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        return response.body();
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
    }

    private static void assertHistoryPagination(int port, String sessionId) throws Exception {
        JsonNode first = listEvents(port, sessionId, "limit=1");
        assertThat(first.path("events").get(0).path("type").asText()).isEqualTo("user.message");
        String cursor = first.path("next_page").asText();
        assertThat(cursor).startsWith("page_").doesNotContain(sessionId);

        JsonNode second = listEvents(port, sessionId, "limit=1&page=" + cursor);
        assertThat(second.path("events").get(0).path("type").asText()).isEqualTo("assistant.message.completed");
        assertThat(second.path("events")
                        .get(0)
                        .path("message")
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("process-level answer");
        assertThat(second.path("next_page").isNull()).isTrue();
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
                    assertThat(rows.getLong(2)).isEqualTo(3L);
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
        private static final byte[] RESPONSE = ("data: {\"id\":\"chatcmpl-process\",\"object\":"
                        + "\"chat.completion.chunk\",\"created\":1786980000,\"model\":\"runtime-smoke-model\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-process\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1786980000,\"model\":\"runtime-smoke-model\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"process-level answer\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-process\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1786980000,\"model\":\"runtime-smoke-model\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,"
                        + "\"completion_tokens\":3,\"total_tokens\":7}}\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;

        private volatile String requestPath;

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

        private void respond(HttpExchange exchange) throws IOException {
            requestPath = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, RESPONSE.length);
            exchange.getResponseBody().write(RESPONSE);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
