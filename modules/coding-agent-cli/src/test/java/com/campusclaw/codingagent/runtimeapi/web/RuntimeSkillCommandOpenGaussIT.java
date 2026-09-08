/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.AGENT_ID;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CLIENT;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.JWT;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MODEL_ID;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SKILL_NAME;
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
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.startRuntime;
import static com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.submitUserEventAsync;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import com.campusclaw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SessionViewDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证 Skill 真实 HTTP 输入在模型、SSE、数据库、历史及新 JVM 恢复中保持同源。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSkillCommandOpenGaussIT {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPreserveExpandedInputAcrossHttpAndNewJvm(boolean withAttachments) throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        String original = Files.readString(skillFile(), StandardCharsets.UTF_8);
        String arguments = withAttachments ? "  分析订单甲\n并保留说明末尾空白  " : null;
        List<String> files = withAttachments ? List.of("file_b", "file_a") : List.of();
        String expected = arguments == null ? original : original + "\n\n" + arguments;
        String body = skillBody(arguments, files);
        assertThat(MAPPER.readTree(body).properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyElementsOf(withAttachments ? List.of("name", "arguments", "fileIds") : List.of("name"));
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model) {
            model.start();
            int firstPort = freePort();
            var first = startRuntime(config, tempDir, firstPort, modelPort);
            SavedSkillRunDTO saved;
            try (first) {
                awaitHealth(first, firstPort);
                saved = executeFirstSkill(config, model, firstPort, body, expected, files);
            }
            assertThat(first.process().isAlive()).isFalse();
            String updated = original.replace(
                    "Inspect the requested orders and summarize the result.",
                    "New revision: inspect only the newly selected orders.");
            assertThat(updated).isNotEqualTo(original);
            Files.writeString(skillFile(), updated, StandardCharsets.UTF_8);
            assertRestoredInNewJvm(config, model, modelPort, saved, updated);
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void shouldRejectUnboundInvalidAndBusyCallsWithoutAcceptingAnotherMessage() throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int port = freePort();
        int modelPort = freePort();
        var model = new ModelStub(modelPort);
        try (model) {
            model.start();
            var runtime = startRuntime(config, tempDir, port, modelPort);
            try (runtime) {
                awaitHealth(runtime, port);
                String sessionId = createSession(port);
                SessionViewDTO initial = getSession(port, sessionId);
                assertUnboundRejected(port, sessionId);
                assertError(
                        send(skillRequest(port, sessionId, skillBody(null, List.of("file_a", "file_a")))
                                .build()),
                        400,
                        "INVALID_COMMAND_REQUEST");
                assertThat(history(port, sessionId)).isEmpty();
                assertThat(databaseEntries(config, sessionId)).isEmpty();
                assertThat(getSession(port, sessionId)).isEqualTo(initial);
                assertThat(model.requestCount()).isZero();
                var gate = model.blockNextResponse();
                var response = submitSkill(port, sessionId, skillBody(null, List.of()));
                try {
                    gate.awaitRequest();
                    SessionViewDTO running = getSession(port, sessionId);
                    assertThat(running.result().path("state").asText()).isEqualTo("running");
                    assertError(
                            send(skillRequest(port, sessionId, skillBody(null, List.of()))
                                    .build()),
                            409,
                            "SESSION_BUSY");
                    assertThat(getSession(port, sessionId)).isEqualTo(running);
                    assertTypes(databaseEntries(config, sessionId), "user.message");
                    assertThat(model.requestCount()).isEqualTo(1);
                } finally {
                    gate.release();
                }
                assertThat(successfulSkill(response)).contains("event:stream.end");
                assertTypes(databaseEntries(config, sessionId), "user.message", "assistant.message.completed");
                assertThat(getSession(port, sessionId).result().path("state").asText())
                        .isEqualTo("idle");
            }
            assertThat(runtime.process().isAlive()).isFalse();
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    private SavedSkillRunDTO executeFirstSkill(
            ProcessTestConfigDTO config, ModelStub model, int port, String body, String expected, List<String> files)
            throws Exception {
        String sessionId = createSession(port);
        String stream = successfulSkill(submitSkill(port, sessionId, body));
        JsonNode events = history(port, sessionId);
        assertTypes(events, "user.message", "assistant.message.completed");
        assertUserEntry(events.get(0), expected, files);
        assertStreamMatchesHistory(stream, events);
        assertThat(userTexts(model)).containsExactly(modelText(expected, files));
        assertThat(model.lastRequest().path("model").asText()).isEqualTo(MODEL_ID);
        assertThat(model.requestCount()).isEqualTo(1);
        JsonNode stored = databaseEntries(config, sessionId);
        assertTypes(stored, "user.message", "assistant.message.completed");
        assertStoredUser(stored.get(0), events.get(0), expected, files);
        SessionViewDTO view = getSession(port, sessionId);
        assertThat(view.result().path("state").asText()).isEqualTo("idle");
        assertThat(expected).startsWith("---\nname: process-analysis\n");
        return new SavedSkillRunDTO(sessionId, expected, files, view, events, stored);
    }

    private void assertRestoredInNewJvm(
            ProcessTestConfigDTO config, ModelStub model, int modelPort, SavedSkillRunDTO saved, String updated)
            throws Exception {
        int port = freePort();
        var runtime = startRuntime(config, tempDir, port, modelPort);
        try (runtime) {
            awaitHealth(runtime, port);
            assertThat(getSession(port, saved.sessionId())).isEqualTo(saved.session());
            assertThat(history(port, saved.sessionId())).isEqualTo(saved.events());
            assertThat(databaseEntries(config, saved.sessionId())).isEqualTo(saved.entries());
            String ordinary = requireSuccessfulStream(submitUserEventAsync(port, saved.sessionId()));
            assertThat(ordinary).contains("process-level answer 2").doesNotContain("event:stream.error");
            assertThat(userTexts(model)).containsExactly(modelText(saved.text(), saved.fileIds()), "process smoke");
            assertThat(userTexts(model)).noneMatch(text -> text.contains("New revision:"));
            String stream = successfulSkill(submitSkill(port, saved.sessionId(), skillBody(null, List.of())));
            JsonNode events = history(port, saved.sessionId());
            assertTypes(
                    events,
                    "user.message",
                    "assistant.message.completed",
                    "user.message",
                    "assistant.message.completed",
                    "user.message",
                    "assistant.message.completed");
            assertUserEntry(events.get(0), saved.text(), saved.fileIds());
            assertUserEntry(events.get(4), updated, List.of());
            assertStreamMatchesHistory(
                    stream, MAPPER.createArrayNode().add(events.get(4)).add(events.get(5)));
            assertThat(userTexts(model))
                    .containsExactly(modelText(saved.text(), saved.fileIds()), "process smoke", updated);
            JsonNode stored = databaseEntries(config, saved.sessionId());
            assertTypes(
                    stored,
                    "user.message",
                    "assistant.message.completed",
                    "user.message",
                    "assistant.message.completed",
                    "user.message",
                    "assistant.message.completed");
            assertStoredUser(stored.get(0), events.get(0), saved.text(), saved.fileIds());
            assertStoredUser(stored.get(4), events.get(4), updated, List.of());
            assertThat(model.requestCount()).isEqualTo(3);
            assertThat(getSession(port, saved.sessionId())
                            .result()
                            .path("state")
                            .asText())
                    .isEqualTo("idle");
        }
        assertThat(runtime.process().isAlive()).isFalse();
    }

    private void assertUnboundRejected(int port, String sessionId) throws Exception {
        Path original = skillFile().getParent();
        Path unbound = tempDir.resolve("unbound-skill");
        Files.move(original, unbound);
        try {
            assertError(
                    send(skillRequest(port, sessionId, skillBody(null, List.of()))
                            .build()),
                    404,
                    "COMMAND_NOT_FOUND");
        } finally {
            Files.move(unbound, original);
        }
    }

    private static void assertStreamMatchesHistory(String stream, JsonNode history) throws Exception {
        assertThat(stream).doesNotContain("event:stream.error", "event:command.");
        assertThat(stream.indexOf("event:user.message")).isGreaterThanOrEqualTo(0);
        assertThat(stream.indexOf("event:assistant.message.completed"))
                .isGreaterThan(stream.indexOf("event:user.message"));
        assertThat(stream.indexOf("event:stream.end"))
                .isGreaterThan(stream.indexOf("event:assistant.message.completed"));
        List<JsonNode> users = streamEvents(stream, "user.message");
        List<JsonNode> assistants = streamEvents(stream, "assistant.message.completed");
        assertThat(users).hasSize(1);
        assertThat(assistants).hasSize(1);
        ObjectNode expectedUser = history.get(0).deepCopy();
        ObjectNode expectedAssistant = history.get(1).deepCopy();
        expectedUser.remove("type");
        expectedAssistant.remove("type");
        assertThat(users.getFirst()).isEqualTo(expectedUser);
        assertThat(assistants.getFirst()).isEqualTo(expectedAssistant);
    }

    private static List<JsonNode> streamEvents(String stream, String type) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String frame : stream.replace("\r\n", "\n").split("\n\n")) {
            if (frame.lines().noneMatch(line -> line.equals("event:" + type))) {
                continue;
            }
            String json = frame.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).stripLeading())
                    .collect(Collectors.joining("\n"));
            events.add(MAPPER.readTree(json));
        }
        return events;
    }

    private static void assertUserEntry(JsonNode entry, String expected, List<String> files) {
        assertThat(entry.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("type", "entryId", "entrySeq", "message", "fileIds", "createdAt");
        assertThat(entry.path("message").asText()).isEqualTo(expected);
        assertThat(entry.path("fileIds")).isEqualTo(MAPPER.valueToTree(files));
        assertThat(entry.path("entryId").asText()).startsWith("entry_");
        assertThat(entry.path("entrySeq").asLong()).isPositive();
    }

    private static void assertStoredUser(JsonNode row, JsonNode event, String text, List<String> files) {
        assertThat(row.path("entryId")).isEqualTo(event.path("entryId"));
        assertThat(row.path("entrySeq").asLong())
                .isEqualTo(event.path("entrySeq").asLong());
        assertThat(row.path("payload")).isEqualTo(MAPPER.valueToTree(Map.of("message", text, "file_ids", files)));
    }

    private static List<String> userTexts(ModelStub model) {
        List<String> texts = new ArrayList<>();
        for (JsonNode message : model.lastRequest().path("messages")) {
            if (message.path("role").asText().equals("user")) {
                texts.add(message.path("content").asText());
            }
        }
        return texts;
    }

    private static String modelText(String text, List<String> files) {
        return files.isEmpty() ? text : text + "\n\n[File IDs]\n- file_id: file_b\n- file_id: file_a";
    }

    private static CompletableFuture<HttpResponse<String>> submitSkill(int port, String sessionId, String body) {
        return CLIENT.sendAsync(
                skillRequest(port, sessionId, body).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpRequest.Builder skillRequest(int port, String sessionId, String body) {
        return HttpRequest.newBuilder(sessionUri(port, sessionId, "command"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US")
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer " + JWT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private static String skillBody(String arguments, List<String> files) {
        ObjectNode body = MAPPER.createObjectNode().put("name", "skill:" + SKILL_NAME);
        if (arguments != null) {
            body.put("arguments", arguments);
        }
        if (!files.isEmpty()) {
            body.set("fileIds", MAPPER.valueToTree(files));
        }
        return body.toString();
    }

    private static String successfulSkill(CompletableFuture<HttpResponse<String>> future) throws Exception {
        var response = future.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Language")).contains("en-US");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(response.body()).doesNotContain("\"resCode\"", "\"commandId\"");
        return response.body();
    }

    private static JsonNode history(int port, String sessionId) throws Exception {
        var response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, "limit=100"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.path("resCode").asText()).isEqualTo("0");
        assertThat(envelope.path("result").path("nextPage").isNull()).isTrue();
        return envelope.path("result").path("events");
    }

    private static JsonNode databaseEntries(ProcessTestConfigDTO config, String sessionId) throws Exception {
        var entries = MAPPER.createArrayNode();
        try (var connection = DriverManager.getConnection(
                        config.databaseUrl(), config.databaseUser(), config.databasePassword());
                var statement = connection.prepareStatement(
                        "SELECT id, entry_seq, type, payload FROM campusclaw_session.t_session_entries WHERE session_id = ? ORDER BY entry_seq")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    ObjectNode row = entries.addObject()
                            .put("entryId", rows.getString(1))
                            .put("entrySeq", rows.getLong(2))
                            .put("type", rows.getString(3));
                    row.set("payload", MAPPER.readTree(rows.getString(4)));
                }
            }
        }
        return entries;
    }

    private static void assertTypes(JsonNode events, String... expected) {
        assertThat(events).extracting(event -> event.path("type").asText()).containsExactly(expected);
    }

    private static void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode envelope = MAPPER.readTree(response.body());
        assertThat(envelope.properties()).extracting(Map.Entry::getKey).containsExactly("resCode", "resMsg");
        assertThat(envelope.path("resCode").asText()).isEqualTo(code);
    }

    private Path skillFile() {
        return tempDir.resolve("agent")
                .resolve(AGENT_ID)
                .resolve(".campusclaw/skills")
                .resolve(SKILL_NAME)
                .resolve("SKILL.md");
    }

    private record SavedSkillRunDTO(
            String sessionId,
            String text,
            List<String> fileIds,
            SessionViewDTO session,
            JsonNode events,
            JsonNode entries) {}
}
