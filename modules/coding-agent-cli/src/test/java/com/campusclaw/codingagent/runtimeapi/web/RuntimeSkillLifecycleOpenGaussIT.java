/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.AGENT_ID;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CLIENT;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SKILL_NAME;
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

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.RuntimeProcess;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 使用实际 HTTP、独立 JVM 和 openGauss 验证 Skill 断线、执行超时及运行资源复用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSkillLifecycleOpenGaussIT {
    @Test
    void testAcceptedSkillContinuesAfterSocketResetAndReleasesCapacity(@TempDir Path directory) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        prepareRuntimeFiles(directory);
        String content = skillContent(directory);
        int port = freePort();
        int modelPort = freePort();
        ModelStub model = new ModelStub(modelPort);
        RuntimeProcess runtime;
        try (model) {
            model.start();
            runtime = startLimitedRuntime(config, directory, port, modelPort, "30m");
            try (runtime) {
                awaitHealth(runtime, port);
                String sessionId = createSession(port);
                String competitor = createSession(port);
                var gate = model.blockNextResponse();
                try {
                    try (Socket socket = openSkillSocket(port, sessionId, "断线前已接受")) {
                        gate.awaitRequest();
                        assertAcceptedInput(port, sessionId, model, content + "\n\n断线前已接受");
                        resetSocket(socket);
                    }
                    assertStillRunningAfterDisconnect(port, sessionId);
                    assertRejectedWithoutAcceptance(port, sessionId, 409, "SESSION_BUSY", model);
                    assertRejectedWithoutAcceptance(port, competitor, 503, "RUNTIME_CAPACITY_EXCEEDED", model);
                    gate.release();
                    assertTerminalHistory(port, sessionId, "stop");
                    assertThat(model.requestCount()).isEqualTo(1);
                    assertCapacityReusable(port, sessionId, competitor, model, content);
                } finally {
                    gate.release();
                }
            }
            assertThat(runtime.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testSkillTimeoutPersistsAbortedResultAndReleasesCapacity(boolean disconnect, @TempDir Path directory)
            throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        prepareRuntimeFiles(directory);
        String content = skillContent(directory);
        int port = freePort();
        int modelPort = freePort();
        ModelStub model = new ModelStub(modelPort);
        RuntimeProcess runtime;
        try (model) {
            model.start();
            runtime = startLimitedRuntime(config, directory, port, modelPort, "3s");
            try (runtime) {
                awaitHealth(runtime, port);
                String sessionId = createSession(port);
                String competitor = createSession(port);
                timeoutBlockedSkill(port, sessionId, competitor, model, content, disconnect);
                assertCapacityReusable(port, sessionId, competitor, model, content);
            }
            assertThat(runtime.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    private static void timeoutBlockedSkill(
            int port, String sessionId, String competitor, ModelStub model, String content, boolean disconnect)
            throws Exception {
        var gate = model.blockNextResponse();
        long started = System.nanoTime();
        try {
            if (disconnect) {
                try (Socket socket = openSkillSocket(port, sessionId, "等待超时")) {
                    gate.awaitRequest();
                    assertAcceptedInput(port, sessionId, model, content + "\n\n等待超时");
                    resetSocket(socket);
                }
            } else {
                CompletableFuture<HttpResponse<String>> response = CLIENT.sendAsync(
                        skillRequest(port, sessionId, "等待超时"),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                gate.awaitRequest();
                assertAcceptedInput(port, sessionId, model, content + "\n\n等待超时");
                assertRejectedWithoutAcceptance(port, competitor, 503, "RUNTIME_CAPACITY_EXCEEDED", model);
                assertThat(response).isNotDone();
                assertTimeoutStream(response.get(8, TimeUnit.SECONDS));
            }
            JsonNode terminal = assertTerminalHistory(port, sessionId, "aborted");
            assertThat(terminal.get(1).path("message").path("content")).isEmpty();
            assertThat(getSession(port, sessionId)
                            .result()
                            .required("lifetimeUsage")
                            .required("totalTokens"))
                    .isEqualTo(MAPPER.getNodeFactory().numberNode(0));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
            assertThat(model.requestCount()).isEqualTo(1);
        } finally {
            // 先验证服务自行超时，再释放迟到的模型响应，不能由测试放行触发完成。
            gate.release();
        }
    }

    private static RuntimeProcess startLimitedRuntime(
            ProcessTestConfigDTO config, Path directory, int port, int modelPort, String maximumDuration)
            throws Exception {
        return startRuntime(
                config,
                directory,
                port,
                modelPort,
                "--campusclaw.runtime.execution.max-active=1",
                "--campusclaw.runtime.events.heartbeat-interval=100ms",
                "--campusclaw.runtime.execution.max-duration=" + maximumDuration);
    }

    private static void assertStillRunningAfterDisconnect(int port, String sessionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        do {
            assertThat(getSession(port, sessionId).result().path("state").asText())
                    .isEqualTo("running");
            assertThat(history(port, sessionId)).hasSize(1);
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
    }

    private static String skillContent(Path directory) throws Exception {
        return Files.readString(
                directory
                        .resolve("agent")
                        .resolve(AGENT_ID)
                        .resolve(".campusclaw/skills")
                        .resolve(SKILL_NAME)
                        .resolve("SKILL.md"),
                StandardCharsets.UTF_8);
    }

    private static void assertAcceptedInput(int port, String sessionId, ModelStub model, String expected)
            throws Exception {
        assertThat(getSession(port, sessionId).result().path("state").asText()).isEqualTo("running");
        JsonNode entries = history(port, sessionId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("type").asText()).isEqualTo("user.message");
        assertThat(entries.get(0).path("message").asText()).isEqualTo(expected);
        assertThat(entries.get(0).path("fileIds")).isEmpty();
        assertThat(model.lastRequest().path("messages").toString()).contains(MAPPER.writeValueAsString(expected));
        assertThat(model.requestCount()).isEqualTo(1);
    }

    private static void assertRejectedWithoutAcceptance(
            int port, String sessionId, int status, String code, ModelStub model) throws Exception {
        var before = getSession(port, sessionId);
        JsonNode beforeHistory = history(port, sessionId);
        int requests = model.requestCount();
        HttpResponse<String> rejected = send(skillRequest(port, sessionId, "不得接收"));
        assertThat(rejected.statusCode()).isEqualTo(status);
        assertThat(rejected.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(MAPPER.readTree(rejected.body()).path("resCode").asText()).isEqualTo(code);
        assertThat(getSession(port, sessionId)).isEqualTo(before);
        assertThat(history(port, sessionId)).isEqualTo(beforeHistory);
        assertThat(model.requestCount()).isEqualTo(requests);
    }

    private static JsonNode assertTerminalHistory(int port, String sessionId, String reason) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode entries = history(port, sessionId);
            if (entries.size() == 2
                    && "idle"
                            .equals(getSession(port, sessionId)
                                    .result()
                                    .path("state")
                                    .asText())) {
                assertThat(entries.get(0).path("type").asText()).isEqualTo("user.message");
                assertThat(entries.get(1).path("type").asText()).isEqualTo("assistant.message.completed");
                assertThat(entries.get(1).path("finishReason").asText()).isEqualTo(reason);
                return entries;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Accepted Skill did not finish with persisted idle state and two entries");
    }

    private static void assertCapacityReusable(
            int port, String sessionId, String competitor, ModelStub model, String content) throws Exception {
        JsonNode saved = history(port, sessionId);
        assertSuccessfulSkill(send(skillRequest(port, sessionId, "原会话续跑")));
        assertThat(model.lastRequest().path("messages").toString())
                .contains(MAPPER.writeValueAsString(saved.get(0).path("message").asText()))
                .contains(MAPPER.writeValueAsString(content + "\n\n原会话续跑"));
        assertThat(model.lastRequest().path("messages").findValuesAsText("role"))
                .filteredOn("assistant"::equals)
                .hasSize("aborted".equals(saved.get(1).path("finishReason").asText()) ? 0 : 1);
        JsonNode continued = history(port, sessionId);
        assertThat(continued).hasSize(4);
        assertThat(continued.get(0)).isEqualTo(saved.get(0));
        assertThat(continued.get(1)).isEqualTo(saved.get(1));
        assertThat(continued.get(2).path("message").asText()).isEqualTo(content + "\n\n原会话续跑");
        assertThat(getSession(port, sessionId).result().path("state").asText()).isEqualTo("idle");
        assertSuccessfulSkill(send(skillRequest(port, competitor, "其他会话续跑")));
        assertThat(history(port, competitor)).hasSize(2);
        assertThat(history(port, competitor).get(0).path("message").asText()).isEqualTo(content + "\n\n其他会话续跑");
        assertThat(getSession(port, competitor).result().path("state").asText()).isEqualTo("idle");
        assertThat(history(port, sessionId)).isEqualTo(continued);
        assertThat(model.requestCount()).isEqualTo(3);
    }

    private static void assertSuccessfulSkill(HttpResponse<String> response) {
        assertSseHeaders(response);
        assertThat(response.body())
                .contains("event:stream.end", "\"reason\":\"completed\"")
                .doesNotContain("event:stream.error");
    }

    private static void assertTimeoutStream(HttpResponse<String> response) throws Exception {
        assertSseHeaders(response);
        assertThat(response.body())
                .contains("event:stream.error")
                .doesNotContain("event:stream.end", "event:session.status.idle", "TimeoutException");
        List<String> frames = response.body().replace("\r\n", "\n").lines().toList();
        int error = frames.indexOf("event:stream.error");
        assertThat(error).isGreaterThanOrEqualTo(0);
        JsonNode payload = MAPPER.readTree(frames.get(error + 1).substring("data:".length()));
        assertThat(payload.path("resCode").asText()).isEqualTo("SESSION_EXECUTION_FAILED");
        assertThat(payload.path("resMsg").asText()).isNotBlank();
        assertThat(payload.size()).isEqualTo(2);
    }

    private static void assertSseHeaders(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Language")).contains("zh-CN");
    }

    private static HttpRequest skillRequest(int port, String sessionId, String arguments) throws Exception {
        return HttpRequest.newBuilder(sessionUri(port, sessionId, "command"))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "zh-CN")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(skillBody(arguments), StandardCharsets.UTF_8))
                .build();
    }

    private static String skillBody(String arguments) throws Exception {
        return MAPPER.writeValueAsString(Map.of("name", "skill:" + SKILL_NAME, "arguments", arguments));
    }

    private static JsonNode history(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, "limit=20"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("result").path("events");
    }

    private static Socket openSkillSocket(int port, String sessionId, String arguments) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            byte[] body = skillBody(arguments).getBytes(StandardCharsets.UTF_8);
            String headers = "POST " + sessionUri(port, sessionId, "command").getPath() + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\nContent-Type: application/json\r\n"
                    + "Accept-Language: zh-CN\r\nContent-Length: " + body.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            assertRawSseHeaders(socket);
            return socket;
        } catch (Exception | AssertionError failure) {
            socket.close();
            throw failure;
        }
    }

    private static void assertRawSseHeaders(Socket socket) throws Exception {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        while (headers.size() < 8192) {
            int value = socket.getInputStream().read();
            assertThat(value).isNotEqualTo(-1);
            headers.write(value);
            String text = headers.toString(StandardCharsets.ISO_8859_1);
            if (text.endsWith("\r\n\r\n")) {
                assertThat(text).startsWith("HTTP/1.1 200 ");
                assertThat(text.toLowerCase(Locale.ROOT))
                        .contains(
                                "content-type: text/event-stream",
                                "cache-control: no-store",
                                "content-language: zh-cn");
                return;
            }
        }
        throw new AssertionError("SSE response headers exceeded the test header limit");
    }

    private static void resetSocket(Socket socket) throws Exception {
        socket.setSoLinger(true, 0);
        socket.close();
        assertThat(socket.isClosed()).isTrue();
    }
}
