/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider.mate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.ImageContent;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.Tool;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolChoice;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import reactor.netty.http.client.HttpClient;

class MateServiceModelManagerProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private MockWebServer mate;

    private MateServiceModelManagerProvider provider;

    private ListAppender<ILoggingEvent> failureLogs;

    @BeforeEach
    void setUp() throws Exception {
        mate = new MockWebServer();
        mate.start();
        WebClient client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
                .build();
        provider = new MateServiceModelManagerProvider(
                mapper, client, mate.url("/mate-service/v1/LLM/chat").uri(), "openai-completions");
        provider.validateConfiguration();
        failureLogs = new ListAppender<>();
        failureLogs.start();
        failureLogger().addAppender(failureLogs);
    }

    @AfterEach
    void tearDown() throws Exception {
        failureLogger().detachAppender(failureLogs);
        mate.shutdown();
    }

    @Test
    void mapsConversationAndParsesTransparentChatSse() throws Exception {
        mate.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(successSse()));

        AssistantMessage response =
                provider.streamSimple(model(), context(), options()).result().block();

        var recorded = mate.takeRequest();
        JsonNode request = mapper.readTree(recorded.getBody().readUtf8());
        assertEquals(null, recorded.getHeader("Authorization"));
        assertEquals(null, recorded.getHeader("X-HW-ID"));
        assertFalse(request.has("agentId"));
        assertFalse(request.has("sessionId"));
        assertFalse(request.has("apiKey"));
        assertFalse(request.has("baseUrl"));
        assertEquals("managed-model", request.path("model").asText());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertEquals(
                "prior reasoning",
                request.path("messages").get(2).path("reasoning_content").asText());
        assertEquals(
                "call-old", request.path("messages").get(3).path("tool_call_id").asText());
        assertEquals("none", request.path("tool_choice").asText());
        assertEquals(4096, request.path("max_output_tokens").asInt());
        assertFalse(request.has("reasoning_effort"));
        assertEquals("chatcmpl-1", response.responseId());
        assertEquals("managed-model", response.model());
        assertEquals("private-model", response.responseModel());
        assertEquals(StopReason.TOOL_USE, response.stopReason());
        assertEquals(30, response.usage().totalTokens());
        assertTrue(response.content().stream().anyMatch(ThinkingContent.class::isInstance));
        assertTrue(response.content().stream().anyMatch(ToolCall.class::isInstance));
    }

    @Test
    void rejectsImageBeforeCreatingHttpRequest() {
        Context context =
                new Context(null, List.of(new UserMessage(List.of(new ImageContent("AAAA", "image/png")), 1L)), null);

        AssistantMessage error =
                provider.streamSimple(model(), context, null).result().block();

        assertEquals(StopReason.ERROR, error.stopReason());
        assertEquals(MateInvocationErrorCode.UNSUPPORTED_MATE_CHAT_CONTENT.name(), error.errorCode());
        assertNull(error.errorMessage());
        assertEquals(
                1L,
                failureLogs.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN
                                && event.getLoggerName().equals(MateChatRequestMapper.class.getName())
                                && event.getFormattedMessage().contains("errorCode=UNSUPPORTED_MATE_CHAT_CONTENT"))
                        .count());
        assertEquals(0, mate.getRequestCount());
    }

    @Test
    void mapsEstablishedStreamErrorWithoutDone() {
        mate.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: error\ndata: {\"resCode\":\"UPSTREAM_STREAM_ERROR\",\"resMsg\":\"failed\"}\n\n"));

        AssistantMessage error =
                provider.streamSimple(model(), simpleContext(), null).result().block();

        assertEquals(StopReason.ERROR, error.stopReason());
        assertEquals(MateInvocationErrorCode.UPSTREAM_STREAM_ERROR.name(), error.errorCode());
        assertNull(error.errorMessage());
    }

    @Test
    void usesZeroUsageWhenSuccessfulStreamOmitsUsage() {
        mate.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"chatcmpl-no-usage\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"));

        AssistantMessage response =
                provider.streamSimple(model(), simpleContext(), null).result().block();

        assertEquals(StopReason.STOP, response.stopReason());
        assertEquals(Usage.empty(), response.usage());
    }

    @Test
    void mapsCleanCloseWithoutDoneToError() {
        mate.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"chatcmpl-truncated\",\"choices\":[]}\n\n"));

        AssistantMessage error =
                provider.streamSimple(model(), simpleContext(), null).result().block();

        assertEquals(StopReason.ERROR, error.stopReason());
        assertEquals(MateInvocationErrorCode.UPSTREAM_STREAM_ERROR.name(), error.errorCode());
        assertNull(error.errorMessage());
    }

    @Test
    void mapsHttpFailureToCodeWithoutLeakingUpstreamMessage() {
        mate.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"resCode\":\"MODEL_RATE_LIMITED\",\"resMsg\":\"private upstream detail\"}"));

        AssistantMessage error =
                provider.streamSimple(model(), simpleContext(), null).result().block();

        assertEquals(MateInvocationErrorCode.MODEL_RATE_LIMITED.name(), error.errorCode());
        assertNull(error.errorMessage());
        assertTrue(failureLogs.list.stream()
                .anyMatch(event -> event.getLoggerName().equals(MateServiceModelManagerProvider.class.getName())
                        && event.getFormattedMessage().contains("errorCode=MODEL_RATE_LIMITED")
                        && !event.getFormattedMessage().contains("private upstream detail")));
    }

    @Test
    void logsRawSseParseFailureBeforeReturningOnlyCode() {
        mate.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {not-json}\n\ndata: [DONE]\n\n"));

        AssistantMessage error =
                provider.streamSimple(model(), simpleContext(), null).result().block();

        assertEquals(MateInvocationErrorCode.INVALID_CHAT_SSE.name(), error.errorCode());
        assertNull(error.errorMessage());
        assertEquals(
                1L,
                failureLogs.list.stream()
                        .filter(event -> event.getLoggerName().equals(MateChatSseParser.class.getName())
                                && event.getFormattedMessage().contains("errorCode=INVALID_CHAT_SSE")
                                && event.getThrowableProxy() != null)
                        .count());
    }

    @Test
    void codeOnlyInvocationExceptionHasNoCauseOrDetailMessage() {
        var error = new MateModelInvocationException(MateInvocationErrorCode.MANAGER_UNAVAILABLE);

        assertEquals("MANAGER_UNAVAILABLE", error.getMessage());
        assertEquals(MateInvocationErrorCode.MANAGER_UNAVAILABLE, error.errorCode());
        assertNull(error.getCause());
        assertEquals(0, error.getStackTrace().length);
    }

    @Test
    void rejectsUnregisteredProtocolAtStartup() {
        var invalid = new MateServiceModelManagerProvider(
                mapper,
                WebClient.create(),
                mate.url("/mate-service/v1/LLM/chat").uri(),
                "unknown-api");

        assertThrows(IllegalStateException.class, invalid::validateConfiguration);
    }

    @Test
    void rejectsInvalidSharedBaseUrlAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MateServiceModelManagerProvider(
                        mapper,
                        "ftp://campusmate.example.com",
                        "/mate-service/v1/LLM/chat",
                        "openai-completions",
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(2L)));
    }

    @Test
    void rejectsChatPathOutsideMateServiceAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MateServiceModelManagerProvider(
                        mapper,
                        "https://campusmate.example.com",
                        "/other-service/v1/LLM/chat",
                        "openai-completions",
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(2L)));
    }

    @Test
    void rejectsDotSegmentInChatPathAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MateServiceModelManagerProvider(
                        mapper,
                        "https://campusmate.example.com",
                        "/mate-service/v1/LLM/./chat",
                        "openai-completions",
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(2L)));
    }

    @Test
    void closesHttpStreamAndReturnsAbortedWhenCallerCancels() {
        String firstChunk = "data: {\"id\":\"chatcmpl-cancel\",\"model\":\"private-model\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}\n\n";
        mate.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(firstChunk + " ".repeat(100_000))
                .throttleBody(256, 1, TimeUnit.SECONDS));

        var stream = provider.streamSimple(model(), simpleContext(), null);
        AssistantMessageEvent first = stream.asFlux().take(1).blockLast();
        AssistantMessage aborted = stream.result().block();

        assertTrue(first instanceof AssistantMessageEvent.StartEvent);
        assertEquals(StopReason.ABORTED, aborted.stopReason());
        assertEquals("chatcmpl-cancel", aborted.responseId());
        assertEquals(1, mate.getRequestCount());
    }

    private Context context() {
        String signature = MateReasoningSignature.encode(
                mapper, "reasoning_content", mapper.getNodeFactory().textNode("prior reasoning"));
        AssistantMessage assistant = new AssistantMessage(
                List.of(
                        new ThinkingContent("prior reasoning", signature, false),
                        new ToolCall("call-old", "read_file", Map.of("path", "README.md"))),
                Api.OPENAI_COMPLETIONS.value(),
                Provider.MATE_MODEL_MANAGER.value(),
                "managed-model",
                "old-response",
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                2L);
        ToolResultMessage result = new ToolResultMessage(
                "call-old", "read_file", List.of(new TextContent("file contents")), null, false, 3L);
        Tool tool =
                new Tool("read_file", "Read a file", mapper.createObjectNode().put("type", "object"));
        return new Context("system prompt", List.of(new UserMessage("read", 1L), assistant, result), List.of(tool));
    }

    private Context simpleContext() {
        return new Context(null, List.of(new UserMessage("hello", 1L)), null);
    }

    private static SimpleStreamOptions options() {
        return SimpleStreamOptions.builder()
                .toolChoice(ToolChoice.NONE)
                .maxTokens(4096)
                .temperature(0.2)
                .build();
    }

    private static Model model() {
        return new Model(
                "managed-model",
                "Managed model",
                Api.OPENAI_COMPLETIONS,
                Provider.MATE_MODEL_MANAGER,
                null,
                true,
                List.of(InputModality.TEXT),
                new ModelCost(1, 2, 0.5, 0),
                128_000,
                8192,
                null,
                null,
                null);
    }

    private static String successSse() {
        return "data: {\"id\":\"chatcmpl-1\",\"model\":\"private-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"reasoning_content\":\"new reasoning\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"model\":\"private-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"answer\",\"tool_calls\":[{\"index\":0,\"id\":\"call-new\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"pom.xml\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"model\":\"private-model\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10,\"total_tokens\":30,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":5}}}\n\n"
                + "data: [DONE]\n\n";
    }

    private static Logger failureLogger() {
        return (Logger) LoggerFactory.getLogger("com.campusclaw.ai.provider.mate");
    }
}
