/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.AGENT_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CHAT_PATH;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SKILL_NAME;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.RuntimeProcess;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 使用实际服务 JAR 和 openGauss 验证命令清单、状态过滤、双语错误与只读边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCommandCatalogOpenGaussIT {
    @ParameterizedTest
    @ValueSource(strings = {"zh-CN", "en-US"})
    void testActualCatalogDuringIdleRunningAndFailures(String language, @TempDir Path tempDir) throws Exception {
        var config = loadConfiguration();
        int port = freePort();
        int modelPort = freePort();
        prepareRuntimeFiles(tempDir);
        ModelStub model = new ModelStub(modelPort);
        RuntimeProcess runtime;
        try (model) {
            model.start();
            runtime = startRuntime(config, tempDir, port, modelPort);
            try (runtime) {
                awaitHealth(runtime, port);
                String sessionId = createSession(port);
                assertThat(getSession(port, sessionId).result().path("state").asText())
                        .isEqualTo("idle");
                assertThat(history(port, sessionId)).isEmpty();
                assertCatalog(readOnlyCatalog(port, sessionId, language, model), false, true, language);
                assertThat(model.requestCount()).isZero();
                assertRunningCatalog(tempDir, port, sessionId, language, model);
                assertSessionErrors(port, language);
                assertMissingCache(tempDir, port, sessionId, language, model);
                assertCatalog(readOnlyCatalog(port, sessionId, language, model), false, true, language);
                assertThat(model.requestCount()).isOne();
            }
            assertThat(runtime.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void testCompleteEmptyBindingsStillReturnSevenBuiltins(@TempDir Path tempDir) throws Exception {
        var config = loadConfiguration();
        int port = freePort();
        int modelPort = freePort();
        prepareRuntimeFiles(tempDir);
        Files.move(cacheDirectory(tempDir).resolve("skills").resolve(SKILL_NAME), tempDir.resolve("unbound-skill"));
        ModelStub model = new ModelStub(modelPort);
        RuntimeProcess runtime;
        try (model) {
            model.start();
            runtime = startRuntime(config, tempDir, port, modelPort);
            try (runtime) {
                awaitHealth(runtime, port);
                String sessionId = createSession(port);
                assertCatalog(readOnlyCatalog(port, sessionId, "zh-CN", model), false, false, "zh-CN");
                assertThat(history(port, sessionId)).isEmpty();
                assertThat(model.requestCount()).isZero();
            }
            assertThat(runtime.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    private static void assertRunningCatalog(Path tempDir, int port, String sessionId, String language, ModelStub model)
            throws Exception {
        var gate = model.blockNextResponse();
        var stream = submitUserEventAsync(port, sessionId);
        try {
            gate.awaitRequest();
            assertThat(getSession(port, sessionId).result().path("state").asText())
                    .isEqualTo("running");
            assertThat(model.requestPath()).isEqualTo(CHAT_PATH);
            assertThat(history(port, sessionId)).hasSize(1);
            assertCatalog(readOnlyCatalog(port, sessionId, language, model), true, false, language);
            assertMissingCache(tempDir, port, sessionId, language, model);
        } finally {
            gate.release();
        }
        assertThat(requireSuccessfulStream(stream))
                .contains("process-level answer", "\"type\":\"session.status_idle\"")
                .doesNotContain("event:", "stream.error");
        assertThat(getSession(port, sessionId).result().path("state").asText()).isEqualTo("idle");
        assertThat(StreamSupport.stream(history(port, sessionId).spliterator(), false)
                        .map(event -> event.path("type").asText())
                        .toList())
                .containsExactly("user.message", "agent.message", "session.status_idle");
    }

    private static void assertSessionErrors(int port, String language) throws Exception {
        boolean chinese = "zh-CN".equals(language);
        assertError(
                getCatalog(port, "session-00000000000000000000000000000000", language),
                404,
                "SESSION_NOT_FOUND",
                chinese ? "指定的 Session 不存在。" : "The specified Session does not exist.",
                language);
        assertError(
                getCatalog(port, "bad", language),
                400,
                "INVALID_SESSION_ID",
                chinese ? "sessionId 格式不正确。" : "The sessionId format is invalid.",
                language);
    }

    private static void assertMissingCache(Path tempDir, int port, String sessionId, String language, ModelStub model)
            throws Exception {
        Path identity = cacheDirectory(tempDir).resolve("agent.json");
        Path saved = tempDir.resolve("saved-agent.json");
        Files.move(identity, saved);
        try {
            assertError(
                    readOnlyCatalog(port, sessionId, language, model),
                    503,
                    "AGENT_NOT_AVAILABLE",
                    "zh-CN".equals(language) ? "指定的 Agent 当前不可用。" : "The specified Agent is currently unavailable.",
                    language);
            assertThat(identity).doesNotExist();
        } finally {
            Files.move(saved, identity);
        }
    }

    private static HttpResponse<String> readOnlyCatalog(int port, String sessionId, String language, ModelStub model)
            throws Exception {
        var sessionBefore = getSession(port, sessionId);
        JsonNode eventsBefore = history(port, sessionId);
        int requestsBefore = model.requestCount();
        HttpResponse<String> response = getCatalog(port, sessionId, language);
        assertThat(getSession(port, sessionId)).isEqualTo(sessionBefore);
        assertThat(history(port, sessionId)).isEqualTo(eventsBefore);
        assertThat(model.requestCount()).isEqualTo(requestsBefore);
        return response;
    }

    private static HttpResponse<String> getCatalog(int port, String sessionId, String language) throws Exception {
        return send(HttpRequest.newBuilder(sessionUri(port, sessionId, "commands"))
                .header("Accept-Language", language)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
    }

    private static JsonNode history(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, "limit=10"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("result").path("events");
    }

    private static void assertCatalog(HttpResponse<String> response, boolean running, boolean hasSkill, String language)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertHeaders(response, language);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        JsonNode body = MAPPER.readTree(response.body());
        assertFields(body, "resCode", "resMsg", "result");
        assertThat(body.path("resCode").asText()).isEqualTo("0");
        assertThat(body.path("resMsg").asText()).isEqualTo("success");
        assertFields(body.path("result"), "commands");
        JsonNode commands = body.path("result").path("commands");
        assertThat(commands.isArray()).isTrue();
        List<String> expected = new ArrayList<>(
                running
                        ? List.of("help", "status", "name", "model", "thinking", "skills")
                        : List.of("help", "status", "name", "model", "thinking", "compact", "skills"));
        if (hasSkill) {
            expected.add("skill:process-analysis");
        }
        assertThat(StreamSupport.stream(commands.spliterator(), false)
                        .map(command -> command.path("name").asText())
                        .toList())
                .containsExactlyElementsOf(expected);
        assertDescriptors(commands, running);
    }

    private static void assertDescriptors(JsonNode commands, boolean running) throws Exception {
        Map<String, String> hints = running
                ? Map.of("name", "[displayName]")
                : Map.of("name", "[displayName]", "model", "[modelId]", "thinking", "[on|off]");
        for (JsonNode command : commands) {
            String name = command.path("name").asText();
            boolean skill = name.startsWith("skill:");
            assertThat(command.path("kind").asText()).isEqualTo(skill ? "skill" : "builtin");
            assertThat(command.path("description").asText()).isNotBlank();
            if (skill) {
                assertFields(command, "name", "kind", "description", "input");
                assertThat(command.path("description").asText()).isEqualTo("分析测试订单");
                assertThat(command.path("input"))
                        .isEqualTo(MAPPER.readTree("{\"hint\":\"[request]\",\"acceptsFiles\":true}"));
            } else if (hints.containsKey(name)) {
                assertFields(command, "name", "kind", "description", "input");
                assertFields(command.path("input"), "hint");
                assertThat(command.path("input").path("hint").asText()).isEqualTo(hints.get(name));
            } else {
                assertFields(command, "name", "kind", "description");
            }
        }
    }

    private static void assertError(
            HttpResponse<String> response, int status, String code, String message, String language) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertHeaders(response, language);
        assertThat(MAPPER.readTree(response.body()))
                .isEqualTo(MAPPER.valueToTree(Map.of("resCode", code, "resMsg", message)));
        if (status == 503) {
            assertThat(response.headers().firstValue("Retry-After")).contains("3");
        } else {
            assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        }
    }

    private static void assertHeaders(HttpResponse<String> response, String language) {
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue("Content-Language")).contains(language);
        assertThat(response.headers().firstValue("ETag")).isEmpty();
    }

    private static void assertFields(JsonNode node, String... expected) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(expected);
    }

    private static Path cacheDirectory(Path tempDir) {
        return tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw");
    }
}
