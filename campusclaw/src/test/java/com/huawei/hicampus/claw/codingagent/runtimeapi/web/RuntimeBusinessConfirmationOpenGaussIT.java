/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.AGENT_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CALLER_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CHAT_PATH;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.JWT;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.awaitHealth;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.createSession;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.eventsUri;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.freePort;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.getSession;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.loadConfiguration;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.prepareRuntimeFiles;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.requireSuccessfulStream;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.send;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.startRuntime;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.submitUserEventAsync;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ProcessTestConfigDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.RuntimeProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 通过真实 JAR、HTTP、Agent Loop 和 openGauss 验证展示确认问答闭环。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeBusinessConfirmationOpenGaussIT {
    private static final String TOOL_ID = "tool-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String TOOL_CALL_ID = "businessConfirmation-confirm-call";

    @Test
    void answersConfirmationBeforeOriginalToolResultAndContinuesRoot(@TempDir Path directory) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        prepareBusinessConfirmationRuntime(directory);
        Map<String, Object> context = nestedContext();
        Map<String, Object> arguments = confirmationArguments("driver_design_confirm", context);
        int runtimePort = freePort();
        try (BusinessConfirmationMateStub mate = scriptedMate(freePort(), arguments, "已确认驱动配置");
                RuntimeProcess runtime =
                        startBusinessConfirmationRuntime(config, directory, runtimePort, mate.port())) {
            mate.start();
            awaitHealth(runtime, runtimePort);
            String sessionId = createSession(runtimePort);

            List<JsonNode> first = frames(requireSuccessfulStream(submitUserEventAsync(runtimePort, sessionId)));
            String rootEventId = first.getFirst().path("eventId").asText();
            JsonNode question = assertQuestionSegment(first, rootEventId, arguments, context);
            assertWaitingWithoutNextModel(runtimePort, sessionId, mate);

            List<JsonNode> continuation = frames(requireSuccessfulStream(answerQuestion(
                    runtimePort, sessionId, question.path("eventId").asText(), "已确认驱动配置")));
            assertContinuation(
                    continuation, rootEventId, question.path("eventId").asText(), mate);
            List<JsonNode> history = history(runtimePort, sessionId);
            assertHistory(history, rootEventId, context);
            assertThat(mate.modelRequestCount()).isEqualTo(2);
            saveSample(first, continuation, history);
        }
    }

    @Test
    void preservesContextVariantsAndRejectsOversizedAnswer(@TempDir Path directory) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        prepareBusinessConfirmationRuntime(directory);
        Map<String, Object> omitted = confirmationArguments("   ", null);
        Map<String, Object> empty = confirmationArguments("empty_context", Map.of());
        int matePort = freePort();
        int runtimePort = freePort();
        try (BusinessConfirmationMateStub mate = new BusinessConfirmationMateStub(matePort);
                RuntimeProcess runtime = startBusinessConfirmationRuntime(config, directory, runtimePort, matePort)) {
            mate.enqueueModel(toolResponse("omitted-call", omitted));
            mate.enqueueModel(textResponse("omitted complete"));
            mate.enqueueModel(toolResponse("empty-call", empty));
            mate.enqueueModel(textResponse("empty complete"));
            mate.start();
            awaitHealth(runtime, runtimePort);
            verifyOmittedContext(runtimePort, mate);
            verifyEmptyContextAndAnswerBoundary(runtimePort, mate, directory);
            assertThat(mate.toolExecutionCount()).isZero();
        }
    }

    @Test
    void interruptCancelsWaitingConfirmationWithoutToolSuccess(@TempDir Path directory) throws Exception {
        ProcessTestConfigDTO config = loadConfiguration();
        prepareBusinessConfirmationRuntime(directory);
        Map<String, Object> arguments = confirmationArguments("cancel_me", Map.of("value", 1));
        int runtimePort = freePort();
        try (BusinessConfirmationMateStub mate = new BusinessConfirmationMateStub(freePort());
                RuntimeProcess runtime =
                        startBusinessConfirmationRuntime(config, directory, runtimePort, mate.port())) {
            mate.enqueueModel(toolResponse("cancel-call", arguments));
            mate.start();
            awaitHealth(runtime, runtimePort);
            String sessionId = createSession(runtimePort);
            List<JsonNode> first = frames(requireSuccessfulStream(submitUserEventAsync(runtimePort, sessionId)));
            String rootEventId = first.getFirst().path("eventId").asText();
            JsonNode question = one(first, "agent.question");

            List<JsonNode> stopped = frames(requireSuccessfulStream(interrupt(runtimePort, sessionId, rootEventId)));

            assertThat(types(stopped)).startsWith("user.interrupt").contains("session.status_idle");
            assertThat(types(stopped)).doesNotContain("agent.tool_result");
            assertThat(one(stopped, "session.status_idle").path("reason").asText())
                    .isEqualTo("terminated");
            assertThat(mate.modelRequestCount()).isEqualTo(1);
            assertRejectedAnswer(
                    runtimePort, sessionId, question.path("eventId").asText());
            assertThat(mate.toolExecutionCount()).isZero();
        }
    }

    private static RuntimeProcess startBusinessConfirmationRuntime(
            ProcessTestConfigDTO config, Path directory, int runtimePort, int matePort) throws IOException {
        return startRuntime(
                config, directory, runtimePort, matePort, "--campusclaw.runtime.events.heartbeat-interval=100ms");
    }

    private static void prepareBusinessConfirmationRuntime(Path directory) throws Exception {
        prepareRuntimeFiles(directory);
        Path settings = directory.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw/settings.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(settings.toFile());
        ObjectNode binding = root.putArray("bindingTools").addObject();
        binding.put("description", "Confirm with user");
        binding.put("displayName", "用户确认");
        binding.put("id", TOOL_ID);
        binding.put("isConcurrencySafe", "false");
        binding.put("name", "confirm_with_user");
        binding.put("permission", "allow");
        binding.put("source", "builtin-toolset");
        binding.put("version", "1");
        MAPPER.writeValue(settings.toFile(), root);
    }

    private static BusinessConfirmationMateStub scriptedMate(int port, Map<String, Object> arguments, String answer)
            throws Exception {
        BusinessConfirmationMateStub mate = new BusinessConfirmationMateStub(port);
        mate.enqueueModel(toolResponse(TOOL_CALL_ID, arguments));
        mate.enqueueModel(textResponse("模型已使用用户答案：" + answer));
        return mate;
    }

    private static JsonNode assertQuestionSegment(
            List<JsonNode> frames, String rootEventId, Map<String, Object> arguments, Map<String, Object> context) {
        assertThat(types(frames))
                .containsSubsequence("user.message", "agent.tool_call", "agent.question", "session.status_idle")
                .doesNotContain("agent.tool_result");
        JsonNode toolCall = one(frames, "agent.tool_call");
        JsonNode question = one(frames, "agent.question");
        assertThat(toolCall.path("toolCallId").asText()).isEqualTo(TOOL_CALL_ID);
        assertThat(toolCall.path("toolName").asText()).isEqualTo("confirm_with_user");
        assertThat(toolCall.path("arguments")).isEqualTo(MAPPER.valueToTree(arguments));
        assertThat(question.path("toolCallId").asText()).isEqualTo(TOOL_CALL_ID);
        assertThat(question.path("sourceEventId").asText()).isEqualTo(rootEventId);
        assertThat(question.path("label").asText()).isEqualTo("driver_design_confirm");
        assertThat(question.path("context")).isEqualTo(MAPPER.valueToTree(context));
        assertThat(question.has("questions")).isFalse();
        assertThat(one(frames, "session.status_idle").path("reason").asText()).isEqualTo("confirming");
        return question;
    }

    private static void assertWaitingWithoutNextModel(int port, String sessionId, BusinessConfirmationMateStub mate)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
        do {
            assertThat(getSession(port, sessionId).result().path("state").asText())
                    .isEqualTo("running");
            assertThat(mate.modelRequestCount()).isEqualTo(1);
            assertThat(mate.toolExecutionCount()).isZero();
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
    }

    private static void assertContinuation(
            List<JsonNode> frames, String rootEventId, String questionEventId, BusinessConfirmationMateStub mate)
            throws Exception {
        assertThat(frames.getFirst().path("type").asText()).isEqualTo("user.question_answer");
        assertThat(frames.getFirst().path("targetEventId").asText()).isEqualTo(questionEventId);
        assertThat(types(frames))
                .containsSubsequence(
                        "user.question_answer", "agent.tool_result", "agent.message", "session.status_idle");
        JsonNode result = toolResult(frames, TOOL_CALL_ID);
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode content =
                MAPPER.readTree(result.path("content").get(0).path("text").asText());
        assertThat(content.path("content").asText()).isEqualTo("已确认驱动配置");
        assertThat(result.path("sourceEventId").asText()).isEqualTo(rootEventId);
        assertThat(one(frames, "session.status_idle").path("reason").asText()).isEqualTo("done");
        assertProviderContinuation(mate.modelRequest(1));
        assertThat(mate.toolExecutionCount()).isZero();
    }

    private static void assertProviderContinuation(JsonNode request) throws Exception {
        JsonNode tool = findMessage(request.path("messages"), "tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo(TOOL_CALL_ID);
        assertThat(MAPPER.readTree(tool.path("content").asText())
                        .path("content")
                        .asText())
                .isEqualTo("已确认驱动配置");
        JsonNode assistant = request.path("messages").findValue("tool_calls");
        assertThat(assistant.toString()).contains(TOOL_CALL_ID, "confirm_with_user", "driver_design_confirm");
    }

    private static void assertHistory(List<JsonNode> history, String rootEventId, Map<String, Object> context) {
        assertThat(types(history))
                .containsSubsequence(
                        "user.message",
                        "agent.tool_call",
                        "agent.question",
                        "session.status_idle",
                        "user.question_answer",
                        "agent.tool_result",
                        "agent.message",
                        "session.status_idle");
        JsonNode question = one(history, "agent.question");
        assertThat(question.path("sourceEventId").asText()).isEqualTo(rootEventId);
        assertThat(question.path("context")).isEqualTo(MAPPER.valueToTree(context));
        assertThat(toolResult(history, TOOL_CALL_ID).path("sourceEventId").asText())
                .isEqualTo(rootEventId);
    }

    private static void verifyOmittedContext(int port, BusinessConfirmationMateStub mate) throws Exception {
        String sessionId = createSession(port);
        List<JsonNode> first = frames(requireSuccessfulStream(submitUserEventAsync(port, sessionId)));
        JsonNode question = one(first, "agent.question");
        assertThat(question.path("label").asText()).isEqualTo("   ");
        assertThat(question.has("context")).isFalse();
        assertThat(question.has("questions")).isFalse();
        requireSuccessfulStream(
                answerQuestion(port, sessionId, question.path("eventId").asText(), "继续"));
        assertThat(mate.modelRequestCount()).isEqualTo(2);
    }

    private static void verifyEmptyContextAndAnswerBoundary(int port, BusinessConfirmationMateStub mate, Path directory)
            throws Exception {
        String sessionId = createSession(port);
        List<JsonNode> first = frames(requireSuccessfulStream(submitUserEventAsync(port, sessionId)));
        JsonNode question = one(first, "agent.question");
        assertThat(question.path("label").asText()).isEqualTo("empty_context");
        assertThat(question.path("context").isObject()).isTrue();
        assertThat(question.path("context")).isEmpty();
        int requests = mate.modelRequestCount();
        HttpResponse<String> rejected =
                answerQuestionResponse(port, sessionId, question.path("eventId").asText(), "答".repeat(4097));
        if (rejected.statusCode() != 400) {
            Files.copy(
                    directory.resolve("runtime-process-" + port + ".log"),
                    Path.of("/private/tmp/businessConfirmation-confirm-oversized-runtime.log"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
        assertThat(mate.modelRequestCount()).isEqualTo(requests);
        String boundary = "答".repeat(4096);
        List<JsonNode> continuation = frames(requireSuccessfulStream(
                answerQuestion(port, sessionId, question.path("eventId").asText(), boundary)));
        assertThat(MAPPER.readTree(toolResult(continuation, "empty-call")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asText())
                        .path("content")
                        .asText())
                .hasSize(4096);
    }

    private static void assertRejectedAnswer(int port, String sessionId, String questionEventId) throws Exception {
        HttpResponse<String> response = answerQuestionResponse(port, sessionId, questionEventId, "不得继续");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(response.body()).path("resCode").asText()).isEqualTo("QUESTION_ANSWER_NOT_PENDING");
    }

    private static java.util.concurrent.CompletableFuture<HttpResponse<String>> answerQuestion(
            int port, String sessionId, String questionEventId, String custom) throws Exception {
        return answerQuestion(port, sessionId, questionEventId, custom, "confirmation");
    }

    private static java.util.concurrent.CompletableFuture<HttpResponse<String>> answerQuestion(
            int port, String sessionId, String questionEventId, String custom, String answerId) throws Exception {
        return RuntimeHttpProcessFixture.CLIENT.sendAsync(
                answerRequest(port, sessionId, questionEventId, custom, answerId),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> answerQuestionResponse(
            int port, String sessionId, String questionEventId, String custom) throws Exception {
        return send(answerRequest(port, sessionId, questionEventId, custom, "confirmation"));
    }

    private static HttpRequest answerRequest(
            int port, String sessionId, String questionEventId, String custom, String answerId) throws Exception {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "user.question_answer").put("targetEventId", questionEventId);
        event.putArray("answers")
                .addObject()
                .put("id", answerId)
                .putArray("selected")
                .removeAll();
        ((ObjectNode) event.path("answers").get(0)).put("custom", custom);
        String body = MAPPER.createObjectNode().set("event", event).toString();
        return HttpRequest.newBuilder(eventsUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("access-token", "process-access-token")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static java.util.concurrent.CompletableFuture<HttpResponse<String>> interrupt(
            int port, String sessionId, String rootEventId) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "user.interrupt").put("targetEventId", rootEventId);
        String body = MAPPER.createObjectNode().set("event", event).toString();
        HttpRequest request = HttpRequest.newBuilder(eventsUri(port, sessionId, null))
                .header("X-HW-ID", CALLER_ID)
                .header("Authorization", "Bearer " + JWT)
                .header("access-token", "process-access-token")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return RuntimeHttpProcessFixture.CLIENT.sendAsync(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static Map<String, Object> nestedContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("方案", "驱动配置方案");
        context.put("补充说明", null);
        context.put("候选", List.of(Map.of("name", "direct"), Map.of("name", "proxy")));
        return context;
    }

    private static Map<String, Object> confirmationArguments(String label, Map<String, Object> context) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("label", label);
        if (context != null) {
            args.put("context", context);
        }
        return args;
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

    private static JsonNode findMessage(JsonNode messages, String role) {
        for (JsonNode message : messages) {
            if (role.equals(message.path("role").asText())) {
                return message;
            }
        }
        throw new IllegalStateException("provider message is missing");
    }

    private static void saveSample(List<JsonNode> first, List<JsonNode> continuation, List<JsonNode> history)
            throws IOException {
        String output = System.getProperty("businessConfirmation.it.sample", "");
        if (output.isBlank()) {
            return;
        }
        ObjectNode sample = MAPPER.createObjectNode();
        sample.set("questionStream", MAPPER.valueToTree(first));
        sample.set("answerStream", MAPPER.valueToTree(continuation));
        sample.set("history", MAPPER.valueToTree(history));
        Files.writeString(
                Path.of(output), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sample));
    }

    private static String toolResponse(String callId, Map<String, Object> arguments) throws Exception {
        ObjectNode first = chunk(null);
        ObjectNode delta = (ObjectNode) first.path("choices").get(0).path("delta");
        delta.put("role", "assistant");
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", 0).put("id", callId).put("type", "function");
        call.putObject("function")
                .put("name", "confirm_with_user")
                .put("arguments", MAPPER.writeValueAsString(arguments));
        return data(first) + data(usageChunk("tool_calls")) + "data: [DONE]\n\n";
    }

    private static String textResponse(String text) {
        ObjectNode first = chunk(null);
        ((ObjectNode) first.path("choices").get(0).path("delta"))
                .put("role", "assistant")
                .put("content", text);
        return data(first) + data(usageChunk("stop")) + "data: [DONE]\n\n";
    }

    private static ObjectNode usageChunk(String finishReason) {
        ObjectNode last = chunk(finishReason);
        last.putObject("usage")
                .put("prompt_tokens", 4)
                .put("completion_tokens", 3)
                .put("total_tokens", 7);
        return last;
    }

    private static ObjectNode chunk(String finishReason) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-businessConfirmation")
                .put("object", "chat.completion.chunk")
                .put("created", 1786980000)
                .put("model", "runtime-smoke-model");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0).putObject("delta");
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return root;
    }

    private static String data(JsonNode value) {
        return "data: " + value + "\n\n";
    }

    private static final class BusinessConfirmationMateStub implements AutoCloseable {
        private final int port;

        private final HttpServer server;

        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private final Queue<byte[]> modelResponses = new ConcurrentLinkedQueue<>();

        private final AtomicInteger modelRequests = new AtomicInteger();

        private final AtomicInteger toolExecutions = new AtomicInteger();

        private final List<JsonNode> capturedModelRequests = new CopyOnWriteArrayList<>();

        BusinessConfirmationMateStub(int port) throws IOException {
            this.port = port;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(executor);
            server.createContext("/", this::respond);
        }

        int port() {
            return port;
        }

        void enqueueModel(String response) {
            modelResponses.add(response.getBytes(StandardCharsets.UTF_8));
        }

        void start() {
            server.start();
        }

        int modelRequestCount() {
            return modelRequests.get();
        }

        int toolExecutionCount() {
            return toolExecutions.get();
        }

        JsonNode modelRequest(int index) {
            return capturedModelRequests.get(index);
        }

        private void respond(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (CHAT_PATH.equals(path)) {
                    respondModel(exchange);
                } else if (path.equals("/mate-service/v1/agents/" + AGENT_ID)) {
                    respondJson(exchange, agentTools());
                } else if (path.equals("/mate-service/v1/skill/query/" + SKILL_ID)) {
                    respondJson(exchange, emptySkillTools());
                } else if (path.equals("/mate-service/v1/runtime/tools/query")) {
                    respondJson(exchange, toolMetadata());
                } else if (path.endsWith("/execute")) {
                    toolExecutions.incrementAndGet();
                    respondJson(exchange, "{\"resCode\":\"0\",\"result\":{}}");
                } else {
                    exchange.sendResponseHeaders(404, -1L);
                }
            } finally {
                exchange.close();
            }
        }

        private void respondModel(HttpExchange exchange) throws IOException {
            modelRequests.incrementAndGet();
            capturedModelRequests.add(MAPPER.readTree(exchange.getRequestBody()).deepCopy());
            byte[] response = modelResponses.poll();
            if (response == null) {
                exchange.sendResponseHeaders(500, -1L);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }

        private static void respondJson(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private static String agentTools() {
            return "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"" + TOOL_ID
                    + "\"}]}}";
        }

        private static String emptySkillTools() {
            return "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}";
        }

        private static String toolMetadata() {
            return "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[{\"id\":\""
                    + TOOL_ID
                    + "\",\"name\":\"confirm_with_user\",\"display_name\":\"用户确认\","
                    + "\"description\":\"Confirm with user\",\"permission\":\"allow\","
                    + "\"is_concurrency_safe\":false,\"input_schema\":{\"type\":\"object\"},"
                    + "\"output_schema\":{\"type\":\"object\"}}]}}";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            executor.close();
            try {
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("businessConfirmation stub termination interrupted", error);
            }
        }
    }
}
