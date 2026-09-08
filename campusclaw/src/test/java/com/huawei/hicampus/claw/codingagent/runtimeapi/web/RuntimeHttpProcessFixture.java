/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Assumptions;

/**
 * Runtime HTTP 跨进程测试共用的本地目录、模型协议桩和进程资源。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
final class RuntimeHttpProcessFixture {
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

    static final String MODEL_ID = "runtime-smoke-model";

    static final String SECOND_MODEL_ID = "runtime-smoke-model-2";

    static final String SKILL_NAME = "process-analysis";

    static final String SKILL_ID = "skill-0123456789abcdef0123456789abcdef";

    static final String CHAT_PATH = "/mate-service/v1/LLM/chat";

    static final String CALLER_ID = "mate-service";

    static final String JWT = "process-jwt";

    static final String APP_KEY = "process-appkey";

    static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    static RuntimeProcess startRuntime(
            ProcessTestConfigDTO config, Path tempDir, int port, int modelPort, String... startupArguments)
            throws IOException {
        Path log = tempDir.resolve("runtime-process-" + port + ".log");
        ProcessBuilder builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        config.jar().toString(),
                        "--server.address=127.0.0.1",
                        "--server.port=" + port)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.command().addAll(List.of(startupArguments));
        configureEnvironment(builder, config, tempDir, modelPort);
        return new RuntimeProcess(builder.start(), log);
    }

    private static void configureEnvironment(
            ProcessBuilder builder, ProcessTestConfigDTO config, Path tempDir, int modelPort) {
        var environment = builder.environment();
        environment.put("CAMPUSCLAW_HOME", tempDir.resolve("home").toString());
        environment.put("GAUSSDB_URL", config.databaseUrl());
        environment.put("GAUSSDB_USER", config.databaseUser());
        environment.put("GAUSSDB_PASSWORD", config.databasePassword());
        environment.put("GAUSSDB_SCHEMA", "campusclaw_session");
        environment.put("GAUSSDB_SSL_MODE", "disable");
        environment.put("CAMPUSMATE_BASE_URL", "http://127.0.0.1:" + modelPort);
        environment.put("SPRING_APPLICATION_JSON", springConfiguration(tempDir));
    }

    private static String springConfiguration(Path tempDir) {
        ObjectNode runtime = MAPPER.createObjectNode();
        runtime.putObject("events").put("cursor-secret", "process-cursor-secret-at-least-32-bytes");
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("campusclaw").set("runtime", runtime);
        ObjectNode mate = root.putObject("campusmate");
        mate.putObject("runtime").put("agents-root", tempDir.resolve("agent").toString());
        mate.putObject("endpoints").put("model-chat-path", CHAT_PATH);
        return root.toString();
    }

    static void prepareRuntimeFiles(Path tempDir) throws IOException {
        Path managedDirectory = tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw");
        Files.createDirectories(managedDirectory.resolve("agents"));
        Files.createDirectories(managedDirectory.resolve("skills"));
        Files.writeString(managedDirectory.resolve("agent.json"), agentIdentity(), StandardCharsets.UTF_8);
        Files.writeString(managedDirectory.resolve("settings.json"), agentSettings(), StandardCharsets.UTF_8);
        Files.writeString(
                managedDirectory.resolve("SYSTEM.md"), "Deterministic process test agent.", StandardCharsets.UTF_8);
        writeSkill(managedDirectory);
    }

    private static String agentIdentity() {
        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("schemaVersion", 1);
        identity.put("id", AGENT_ID);
        identity.put("name", "process-agent");
        identity.put("displayName", "跨进程测试助手");
        identity.put("version", "1");
        identity.put("enabled", true);
        identity.putArray("description").add("分析订单并生成摘要");
        identity.putArray("userCases").add("请分析本次订单");
        return identity.toString();
    }

    private static void writeSkill(Path managedDirectory) throws IOException {
        Path skillDirectory = managedDirectory.resolve("skills").resolve(SKILL_NAME);
        Files.createDirectories(skillDirectory.resolve("references"));
        Files.createDirectories(skillDirectory.resolve("templates"));
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("id", SKILL_ID);
        manifest.put("name", SKILL_NAME);
        manifest.put("version", "1");
        Files.writeString(skillDirectory.resolve("skill.json"), manifest.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                skillDirectory.resolve("SKILL.md"),
                """
                ---
                name: process-analysis
                description: 分析测试订单
                disable-model-invocation: true
                ---
                Inspect the requested orders and summarize the result.
                """,
                StandardCharsets.UTF_8);
    }

    private static String agentSettings() {
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("schemaVersion", 1);
        settings.put("defaultModel", MODEL_ID);
        settings.putArray("bindingModels").add(MODEL_ID).add(SECOND_MODEL_ID);
        return settings.toString();
    }

    static void awaitHealth(RuntimeProcess runtime, int port) throws Exception {
        URI probe = URI.create("http://127.0.0.1:" + port
                + "/campusclaw-service/v1/sessions/session-00000000000000000000000000000000");
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
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 404) {
                    return;
                }
            } catch (IOException ignored) {
                Thread.sleep(100L);
            }
        }
        throw new AssertionError("Runtime readiness probe timed out:\n" + runtime.logContent());
    }

    static String createSession(int port) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/campusclaw-service/v1/agents/" + AGENT_ID + "/sessions");
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertThat(response.statusCode()).isEqualTo(201);
        return MAPPER.readTree(response.body()).path("result").path("sessionId").asText();
    }

    static CompletableFuture<HttpResponse<String>> submitUserEventAsync(int port, String sessionId) {
        URI uri = eventsUri(port, sessionId, null);
        String body = "{\"message\":\"process smoke\",\"fileIds\":[]}";
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String requireSuccessfulStream(CompletableFuture<HttpResponse<String>> future) throws Exception {
        HttpResponse<String> response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        return response.body();
    }

    static SessionViewDTO getSession(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return sessionView(response);
    }

    static SessionViewDTO sessionView(HttpResponse<String> response) throws Exception {
        String etag = response.headers().firstValue("ETag").orElseThrow();
        return new SessionViewDTO(MAPPER.readTree(response.body()).path("result"), etag);
    }

    static URI sessionUri(int port, String sessionId, String suffix) {
        String base = "http://127.0.0.1:" + port + "/campusclaw-service/v1/sessions/" + sessionId;
        return URI.create(suffix == null ? base : base + "/" + suffix);
    }

    static URI eventsUri(int port, String sessionId, String query) {
        String value = "http://127.0.0.1:" + port + "/campusclaw-service/v1/sessions/" + sessionId + "/events";
        return URI.create(query == null ? value : value + "?" + query);
    }

    static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    record SessionViewDTO(JsonNode result, String etag) {}

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static ProcessTestConfigDTO loadConfiguration() {
        String jarValue = System.getProperty("runtime.it.jar", "");
        ProcessTestConfigDTO config = new ProcessTestConfigDTO(
                jarValue.isBlank() ? null : Path.of(jarValue).toAbsolutePath(),
                System.getProperty("gaussdb.it.url", ""),
                System.getProperty("gaussdb.it.username", ""),
                System.getProperty("gaussdb.it.password", ""));
        Assumptions.assumeTrue(
                isAvailable(config),
                "Set runtime.it.jar and gaussdb.it.url/username/password to run the process integration test");
        return config;
    }

    private static boolean isAvailable(ProcessTestConfigDTO config) {
        return config.jar() != null
                && Files.isRegularFile(config.jar())
                && !config.databaseUrl().isBlank()
                && !config.databaseUser().isBlank()
                && !config.databasePassword().isBlank();
    }

    record ProcessTestConfigDTO(Path jar, String databaseUrl, String databaseUser, String databasePassword) {}

    static final class ModelGate {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        void awaitRequest() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        private void blockResponse() throws InterruptedException {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    static final class RuntimeProcess implements AutoCloseable {
        private final Process process;

        private final Path log;

        private RuntimeProcess(Process process, Path log) {
            this.process = process;
            this.log = log;
        }

        Process process() {
            return process;
        }

        private String logContent() throws IOException {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            assertThat(process.isAlive()).as("Runtime process stopped").isFalse();
        }
    }

    static final class ModelStub implements AutoCloseable {
        private final HttpServer server;

        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private volatile String requestPath;

        private ModelGate pendingGate;

        private final AtomicInteger responseCount = new AtomicInteger();

        private final AtomicInteger requestCount = new AtomicInteger();

        private volatile JsonNode lastRequest;

        ModelStub(int port) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(executor);
            server.createContext("/", this::respond);
        }

        void start() {
            server.start();
        }

        String requestPath() {
            return requestPath;
        }

        int requestCount() {
            return requestCount.get();
        }

        JsonNode lastRequest() {
            return lastRequest;
        }

        boolean executorTerminated() {
            return executor.isTerminated();
        }

        synchronized ModelGate blockNextResponse() {
            if (pendingGate != null) {
                throw new IllegalStateException("a model response is already blocked");
            }
            pendingGate = new ModelGate();
            return pendingGate;
        }

        private void respond(HttpExchange exchange) throws IOException {
            try {
                requestCount.incrementAndGet();
                requestPath = exchange.getRequestURI().getPath();
                if (!CHAT_PATH.equals(requestPath)) {
                    exchange.sendResponseHeaders(404, -1L);
                    return;
                }
                lastRequest = MAPPER.readTree(exchange.getRequestBody());
                awaitGate();
                byte[] response = response(responseCount.incrementAndGet());
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
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
            executor.shutdownNow();
            executor.close();
        }
    }
}
