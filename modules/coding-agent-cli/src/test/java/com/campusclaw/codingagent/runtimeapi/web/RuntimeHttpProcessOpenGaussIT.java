/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.APP_KEY;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CALLER_ID;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CHAT_PATH;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CLIENT;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.JWT;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MODEL_ID;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelGate;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.RuntimeProcess;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SECOND_MODEL_ID;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SessionViewDTO;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.awaitHealth;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.createSession;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.eventsUri;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.freePort;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.getSession;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.loadConfiguration;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.prepareRuntimeFiles;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.requireSuccessfulStream;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.send;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.sessionUri;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.sessionView;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.startRuntime;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.submitUserEventAsync;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 打包 JAR、真实 HTTP/SSE、模型协议桩与 openGauss 的跨进程集成测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeHttpProcessOpenGaussIT {
    @Test
    void testPackagedJarStreamsAndPagesPersistedEvents(@TempDir Path tempDir) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        int applicationPort = freePort();
        int modelPort = freePort();
        prepareRuntimeFiles(tempDir);

        try (ModelStub modelStub = new ModelStub(modelPort);
                RuntimeProcess runtime = startRuntime(config, tempDir, applicationPort, modelPort)) {
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
            assertThat(modelStub.requestPath()).isEqualTo(CHAT_PATH);
        }
    }

    @Test
    void testPackagedJarRestartsWithPersistedHistory(@TempDir Path tempDir) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        int modelPort = freePort();
        prepareRuntimeFiles(tempDir);
        try (ModelStub modelStub = new ModelStub(modelPort)) {
            modelStub.start();
            int firstPort = freePort();
            String sessionId;
            SessionViewDTO beforeRestart;
            RuntimeProcess first = startRuntime(config, tempDir, firstPort, modelPort);
            try (first) {
                awaitHealth(first, firstPort);
                sessionId = createSession(firstPort);
                assertThat(requireSuccessfulStream(submitUserEventAsync(firstPort, sessionId)))
                        .contains("process-level answer");
                beforeRestart = getSession(firstPort, sessionId);
            }
            assertThat(first.process().isAlive()).isFalse();
            int secondPort = freePort();
            try (RuntimeProcess restarted = startRuntime(config, tempDir, secondPort, modelPort)) {
                awaitHealth(restarted, secondPort);
                assertThat(getSession(secondPort, sessionId)).isEqualTo(beforeRestart);
                assertThat(listEvents(secondPort, sessionId, "limit=10").path("events"))
                        .hasSize(2);
                assertThat(requireSuccessfulStream(submitUserEventAsync(secondPort, sessionId)))
                        .contains("process-level answer 2");
                String messages = modelStub.lastRequest().path("messages").toString();
                assertThat(messages).contains("process-level answer");
                assertThat(countOccurrences(messages, "process smoke")).isEqualTo(2);
                assertThat(getSession(secondPort, sessionId)
                                .result()
                                .path("state")
                                .asText())
                        .isEqualTo("idle");
            }
        }
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
        assertThat(result.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(result.path("acceptedAt").asText()).isNotBlank();
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

    private static void assertDelete(int port, ProcessTestConfigDTO config, String sessionId) throws Exception {
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

    private static void assertTombstone(ProcessTestConfigDTO config, String sessionId) throws Exception {
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
        assertThat(stream)
                .contains("\"entryId\":", "\"entrySeq\":", "\"finishReason\":", "\"createdAt\":")
                .doesNotContain("\"entry_id\":", "\"entry_seq\":", "\"finish_reason\":", "\"created_at\":");
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
            cursor = page.path("nextPage").isNull()
                    ? null
                    : page.path("nextPage").asText();
            if (index < expectedTypes.size() - 1) {
                assertThat(cursor).startsWith("page_").doesNotContain(sessionId);
            }
        }
        assertThat(cursor).isNull();
    }

    private static void assertSessionConfiguration(int port, String sessionId) throws Exception {
        SessionViewDTO initial = getSession(port, sessionId);
        assertThat(initial.result().path("thinking").asBoolean()).isTrue();
        JsonNode models = listModels(port, sessionId);
        assertThat(models.path("currentModelId").asText()).isEqualTo(MODEL_ID);
        List<String> availableModels = MAPPER.readerForListOf(String.class).readValue(models.path("models"));
        assertThat(availableModels).containsExactly(MODEL_ID, SECOND_MODEL_ID);

        SessionViewDTO disabled =
                updateConfiguration(port, sessionId, "thinking", initial.etag(), "{\"thinking\":false}");
        assertThat(disabled.result().path("thinking").asBoolean()).isFalse();
        assertThat(disabled.etag()).isNotEqualTo(initial.etag());

        SessionViewDTO changed = updateConfiguration(
                port, sessionId, "model", disabled.etag(), "{\"modelId\":\"" + SECOND_MODEL_ID + "\"}");
        assertThat(changed.result().path("modelId").asText()).isEqualTo(SECOND_MODEL_ID);
        assertThat(changed.result().path("thinking").asBoolean()).isFalse();
        SessionViewDTO unchanged = updateConfiguration(
                port, sessionId, "model", changed.etag(), "{\"modelId\":\"" + SECOND_MODEL_ID + "\"}");
        assertThat(unchanged.etag()).isEqualTo(changed.etag());
        assertStaleConfigurationRejected(port, sessionId, disabled.etag());
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

    private static SessionViewDTO updateConfiguration(
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

    private static JsonNode listEvents(int port, String sessionId, String query) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, query))
                .header("X-HW-ID", CALLER_ID)
                .header("X-HW-APPKEY", APP_KEY)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).path("result");
    }

    private static void assertDatabaseState(ProcessTestConfigDTO config, String sessionId) throws Exception {
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
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("session.thinking.changed");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("session.model.changed");
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
