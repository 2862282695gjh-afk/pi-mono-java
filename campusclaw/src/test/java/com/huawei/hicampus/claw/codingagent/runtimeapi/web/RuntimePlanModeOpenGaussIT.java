/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CALLER_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.JWT;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
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
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SessionViewDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 通过真实 JAR、HTTP、模型请求和 openGauss 验证计划审核续跑闭环。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimePlanModeOpenGaussIT {
    private static final String PLAN = "# 实施方案\n\n1. 执行修改\n2. 验证结果";

    @Test
    void approvedPlanSwitchesTheNextProviderRequestAndPublishesTodo(@TempDir Path tempDir) throws Exception {
        var config = loadConfiguration();
        prepareRuntimeFiles(tempDir);
        int port = freePort();
        int modelPort = freePort();
        var model = scriptedModel(modelPort);
        try (model;
                var runtime = startRuntime(config, tempDir, port, modelPort)) {
            model.start();
            awaitHealth(runtime, port);
            String sessionId = createSession(port);
            updateMode(port, sessionId, getSession(port, sessionId).etag());
            List<JsonNode> first = frames(requireSuccessfulStream(submitUserEventAsync(port, sessionId)));
            String rootEventId = first.getFirst().path("eventId").asText();
            String planEventId = assertConfirming(first, rootEventId);
            List<JsonNode> continuation = frames(requireSuccessfulStream(confirmPlan(port, sessionId, planEventId)));
            assertContinuation(continuation, rootEventId, planEventId);
            assertProviderPrompts(model);
            assertHistoryAndSession(port, sessionId, rootEventId);
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    @Test
    void unifiedUpdateIsAtomicAndKeepsRunningNameIndependent(@TempDir Path tempDir) throws Exception {
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
            long initialVersion = resourceVersion(config, sessionId);
            SessionViewDTO updated = unifiedUpdate(port, sessionId, initial.etag());
            assertUnifiedUpdate(config, port, sessionId, initialVersion, updated);
            assertRunningUpdateRules(port, sessionId, model);
        }
        assertThat(model.executorTerminated()).isTrue();
    }

    private static ModelStub scriptedModel(int port) throws Exception {
        var model = new ModelStub(port);
        model.enqueueResponse(planResponse());
        model.enqueueResponse(toolResponse(
                "todo-call",
                "TodoUpdate",
                Map.of(
                        "todos",
                        List.of(
                                Map.of("content", "执行修改", "status", "completed"),
                                Map.of("content", "验证结果", "status", "in_progress")))));
        model.enqueueResponse(textResponse("修改已完成，正在验证。"));
        return model;
    }

    private static SessionViewDTO unifiedUpdate(int port, String sessionId, String etag) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("displayName", " 联合更新 ");
        body.put("modelId", SECOND_MODEL_ID);
        body.put("thinking", false);
        body.put("mode", "plan");
        HttpResponse<String> response = sessionUpdate(port, sessionId, etag, body.toString());
        assertThat(response.statusCode()).isEqualTo(200);
        return RuntimeHttpProcessFixture.sessionView(response);
    }

    private static void assertUnifiedUpdate(
            ProcessTestConfigDTO config, int port, String sessionId, long initialVersion, SessionViewDTO updated)
            throws Exception {
        assertThat(updated.result().path("displayName").asText()).isEqualTo("联合更新");
        assertThat(updated.result().path("modelId").asText()).isEqualTo(SECOND_MODEL_ID);
        assertThat(updated.result().path("thinking").asBoolean()).isFalse();
        assertThat(updated.result().path("mode").asText()).isEqualTo("plan");
        assertThat(resourceVersion(config, sessionId)).isEqualTo(initialVersion + 1);
        assertThat(getSession(port, sessionId)).isEqualTo(updated);
        assertThat(types(history(port, sessionId)))
                .containsExactly("session.model_changed", "session.thinking_changed", "session.mode_changed");
    }

    private static void assertRunningUpdateRules(int port, String sessionId, ModelStub model) throws Exception {
        var gate = model.blockNextResponse();
        var stream = submitUserEventAsync(port, sessionId);
        try {
            gate.awaitRequest();
            SessionViewDTO running = getSession(port, sessionId);
            assertBusyUpdate(port, sessionId, running.etag(), "{\"modelId\":\"" + SECOND_MODEL_ID + "\"}");
            assertBusyUpdate(port, sessionId, running.etag(), "{\"thinking\":false}");
            assertBusyUpdate(port, sessionId, running.etag(), "{\"mode\":\"plan\"}");
            HttpResponse<String> renamed =
                    sessionUpdate(port, sessionId, running.etag(), "{\"displayName\":\"运行中名称\"}");
            assertThat(renamed.statusCode()).isEqualTo(200);
            SessionViewDTO renamedView = RuntimeHttpProcessFixture.sessionView(renamed);
            assertThat(renamedView.result().path("state").asText()).isEqualTo("running");
            assertThat(renamedView.result().path("displayName").asText()).isEqualTo("运行中名称");
            assertThat(getSession(port, sessionId)).isEqualTo(renamedView);
        } finally {
            gate.release();
        }
        requireSuccessfulStream(stream);
    }

    private static void assertBusyUpdate(int port, String sessionId, String etag, String body) throws Exception {
        HttpResponse<String> response = sessionUpdate(port, sessionId, etag, body);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("SESSION_BUSY");
    }

    private static HttpResponse<String> sessionUpdate(int port, String sessionId, String etag, String body)
            throws Exception {
        return send(HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("If-Match", etag)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private static long resourceVersion(ProcessTestConfigDTO config, String sessionId) throws Exception {
        try (var connection = DriverManager.getConnection(
                        config.databaseUrl(), config.databaseUser(), config.databasePassword());
                var statement = connection.prepareStatement(
                        "SELECT resource_version FROM campusclaw_session.t_sessions WHERE id = ?")) {
            statement.setString(1, sessionId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static List<JsonNode> history(int port, String sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(eventsUri(port, sessionId, "limit=100"))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        List<JsonNode> events = new ArrayList<>();
        MAPPER.readTree(response.body()).path("result").path("events").forEach(events::add);
        return events;
    }

    private static void updateMode(int port, String sessionId, String etag) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(sessionUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("If-Match", etag)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"plan\"}", StandardCharsets.UTF_8))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("result").path("mode").asText())
                .isEqualTo("plan");
    }

    private static CompletableFuture<HttpResponse<String>> confirmPlan(int port, String sessionId, String planEventId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("event")
                .put("type", "user.plan_confirmation")
                .put("targetEventId", planEventId)
                .put("result", "approve");
        HttpRequest request = HttpRequest.newBuilder(eventsUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("access-token", "process-access-token")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return RuntimeHttpProcessFixture.CLIENT.sendAsync(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String assertConfirming(List<JsonNode> frames, String rootEventId) {
        assertThat(types(frames))
                .containsSubsequence("user.message", "agent.tool_call", "agent.plan", "session.status_idle");
        JsonNode toolCall = one(frames, "agent.tool_call");
        List<JsonNode> plans = frames.stream()
                .filter(frame -> "agent.plan".equals(frame.path("type").asText()))
                .toList();
        JsonNode plan = plans.stream()
                .filter(frame -> "completed".equals(frame.path("phase").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode idle = one(frames, "session.status_idle");
        assertThat(toolCall.path("arguments").isEmpty()).isTrue();
        assertThat(plans).anySatisfy(preview -> {
            assertThat(preview.path("phase").asText()).isEqualTo("delta");
            assertThat(preview.has("createdAt")).isFalse();
            assertThat(preview.has("toolCallId")).isFalse();
        });
        assertThat(plans)
                .extracting(frame -> frame.path("eventId").asText())
                .containsOnly(plan.path("eventId").asText());
        assertThat(plan.path("phase").asText()).isEqualTo("completed");
        assertThat(plan.path("content").asText()).isEqualTo(PLAN + "\n");
        assertThat(plan.path("toolCallId").asText()).isEqualTo("exit-call");
        assertThat(plan.path("sourceEventId").asText()).isEqualTo(rootEventId);
        assertThat(idle.path("reason").asText()).isEqualTo("confirming");
        return plan.path("eventId").asText();
    }

    private static void assertContinuation(List<JsonNode> frames, String rootEventId, String planEventId) {
        assertThat(frames.getFirst().path("type").asText()).isEqualTo("user.plan_confirmation");
        assertThat(frames.getFirst().path("targetEventId").asText()).isEqualTo(planEventId);
        assertThat(types(frames))
                .containsSubsequence(
                        "user.plan_confirmation",
                        "session.mode_changed",
                        "agent.tool_result",
                        "agent.tool_call",
                        "agent.tool_result",
                        "agent.todo_updated",
                        "agent.message",
                        "session.status_idle");
        JsonNode mode = one(frames, "session.mode_changed");
        assertThat(mode.path("mode").asText()).isEqualTo("build");
        assertThat(mode.path("reason").asText()).isEqualTo("planApproved");
        JsonNode approved = toolResult(frames, "exit-call");
        assertThat(approved.path("content").get(0).path("text").asText())
                .startsWith("{\"approved\":true}\n\n")
                .contains("Runtime 已切换为 build", "具体工具权限仍须遵守");
        assertThat(approved.toString()).doesNotContain("feedback");
        JsonNode todo = one(frames, "agent.todo_updated");
        assertThat(todo.path("sourceEventId").asText()).isEqualTo(rootEventId);
        assertThat(todo.path("todos")).hasSize(2);
        assertThat(one(frames, "session.status_idle").path("reason").asText()).isEqualTo("done");
    }

    private static void assertProviderPrompts(ModelStub model) {
        assertThat(model.requestCount()).isEqualTo(3);
        String planPrompt =
                model.request(0).path("messages").get(0).path("content").asText();
        String buildPrompt =
                model.request(1).path("messages").get(0).path("content").asText();
        assertThat(planPrompt).contains("你当前处于计划模式。", "你负责调研、必要澄清和提交完整方案。");
        assertThat(buildPrompt).contains("你当前处于 build 模式。", "TodoUpdate 用于 build 阶段的实施进度。");
        assertThat(buildPrompt).doesNotContain("你当前处于计划模式。");
        JsonNode proposal = null;
        for (JsonNode message : model.request(1).path("messages")) {
            if ("assistant".equals(message.path("role").asText())
                    && message.path("content").asText().contains("<proposed_plan>")) {
                proposal = message;
            }
        }
        assertThat(proposal).isNotNull();
        assertThat(proposal.path("content").asText()).contains("<proposed_plan>", PLAN, "</proposed_plan>");
        JsonNode function = proposal.path("tool_calls").get(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("ExitPlanMode");
        assertThat(function.path("arguments").asText()).isEqualTo("{}");
    }

    private static void assertHistoryAndSession(int port, String sessionId, String rootEventId) throws Exception {
        List<JsonNode> events = history(port, sessionId);
        assertThat(types(events)).contains("agent.plan", "user.plan_confirmation", "agent.todo_updated");
        assertThat(events.stream()
                        .filter(event -> "agent.plan".equals(event.path("type").asText()))
                        .toList())
                .singleElement()
                .satisfies(plan -> assertThat(plan.path("phase").asText()).isEqualTo("completed"));
        assertThat(one(events, "agent.todo_updated").path("sourceEventId").asText())
                .isEqualTo(rootEventId);
        var session = getSession(port, sessionId).result();
        assertThat(session.path("state").asText()).isEqualTo("idle");
        assertThat(session.path("mode").asText()).isEqualTo("build");
    }

    private static List<JsonNode> frames(String body) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String line : body.lines().toList()) {
            if (line.startsWith("data:")) {
                String data = line.substring("data:".length()).stripLeading();
                if (!data.equals("[DONE]")) {
                    result.add(MAPPER.readTree(data));
                }
            }
        }
        return result;
    }

    private static List<String> types(List<JsonNode> frames) {
        return frames.stream().map(frame -> frame.path("type").asText()).toList();
    }

    private static JsonNode one(List<JsonNode> frames, String type) {
        return frames.stream()
                .filter(frame -> type.equals(frame.path("type").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode toolResult(List<JsonNode> frames, String toolCallId) {
        return frames.stream()
                .filter(frame -> "agent.tool_result".equals(frame.path("type").asText()))
                .filter(frame -> toolCallId.equals(frame.path("toolCallId").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static String toolResponse(String callId, String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode first = chunk(null);
        ObjectNode delta = (ObjectNode) first.path("choices").get(0).path("delta");
        delta.put("role", "assistant");
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", 0).put("id", callId).put("type", "function");
        call.putObject("function").put("name", toolName).put("arguments", MAPPER.writeValueAsString(arguments));
        ObjectNode last = chunk("tool_calls");
        last.putObject("usage")
                .put("prompt_tokens", 4)
                .put("completion_tokens", 3)
                .put("total_tokens", 7);
        return data(first) + data(last) + "data: [DONE]\n\n";
    }

    private static String planResponse() throws Exception {
        ObjectNode first = chunk(null);
        ObjectNode delta = (ObjectNode) first.path("choices").get(0).path("delta");
        delta.put("role", "assistant").put("content", "方案已整理。\n<proposed_plan>\n" + PLAN + "\n</proposed_plan>");
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", 0).put("id", "exit-call").put("type", "function");
        call.putObject("function").put("name", "ExitPlanMode").put("arguments", "{}");
        ObjectNode last = chunk("tool_calls");
        last.putObject("usage")
                .put("prompt_tokens", 4)
                .put("completion_tokens", 3)
                .put("total_tokens", 7);
        return data(first) + data(last) + "data: [DONE]\n\n";
    }

    private static String textResponse(String text) {
        ObjectNode first = chunk(null);
        ((ObjectNode) first.path("choices").get(0).path("delta"))
                .put("role", "assistant")
                .put("content", text);
        ObjectNode last = chunk("stop");
        last.putObject("usage")
                .put("prompt_tokens", 4)
                .put("completion_tokens", 3)
                .put("total_tokens", 7);
        return data(first) + data(last) + "data: [DONE]\n\n";
    }

    private static ObjectNode chunk(String finishReason) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-plan")
                .put("object", "chat.completion.chunk")
                .put("created", 1786980000)
                .put("model", "runtime-smoke-model");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0).set("delta", MAPPER.createObjectNode());
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return root;
    }

    private static String data(ObjectNode value) {
        return "data: " + value + "\n\n";
    }
}
