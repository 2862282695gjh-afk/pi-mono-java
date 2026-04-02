package com.campusclaw.codingagent.mode.server;

import java.util.Map;

import com.campusclaw.agent.event.AgentEndEvent;
import com.campusclaw.agent.event.MessageEndEvent;
import com.campusclaw.agent.event.MessageStartEvent;
import com.campusclaw.agent.event.MessageUpdateEvent;
import com.campusclaw.agent.event.ToolExecutionEndEvent;
import com.campusclaw.agent.event.ToolExecutionStartEvent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.session.AgentSession;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handles POST /api/chat — streams agent responses as Server-Sent Events.
 */
public class ChatHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentSession session;

    public ChatHandler(AgentSession session) {
        this.session = session;
    }

    record ChatRequest(
            @JsonProperty("message") String message,
            @JsonProperty("model") @Nullable String model,
            @JsonProperty("thinking") @Nullable String thinking
    ) {}

    public Mono<ServerResponse> chat(ServerRequest request) {
        return request.bodyToMono(ChatRequest.class)
                .flatMap(this::handleChat)
                .onErrorResume(Exception.class, e ->
                        ServerResponse.status(500)
                                .bodyValue(Map.of("error", e.getMessage())));
    }

    private Mono<ServerResponse> handleChat(ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "message is required"));
        }

        if (session.isStreaming()) {
            return ServerResponse.status(409)
                    .bodyValue(Map.of("error", "A prompt is already being processed"));
        }

        if (req.model() != null && !req.model().isBlank()) {
            try {
                session.setModel(req.model());
            } catch (Exception e) {
                return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "Invalid model: " + e.getMessage()));
            }
        }

        if (req.thinking() != null && !req.thinking().isBlank()) {
            try {
                session.getAgent().setThinkingLevel(ThinkingLevel.fromValue(req.thinking()));
            } catch (Exception e) {
                return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "Invalid thinking level: " + e.getMessage()));
            }
        }

        Flux<ServerSentEvent<String>> events = Flux.create(sink -> {
            Runnable unsub = session.subscribe(event -> {
                try {
                    if (event instanceof MessageStartEvent) {
                        sink.next(sse("message_start", "{}"));
                    } else if (event instanceof MessageUpdateEvent mu) {
                        var msg = mu.message();
                        if (msg != null) {
                            sink.next(sse("message_update",
                                    MAPPER.writeValueAsString(Map.of("message", msg))));
                        }
                    } else if (event instanceof MessageEndEvent me) {
                        var msg = me.message();
                        sink.next(sse("message_end",
                                MAPPER.writeValueAsString(Map.of(
                                        "message", msg != null ? msg : ""))));
                    } else if (event instanceof ToolExecutionStartEvent te) {
                        sink.next(sse("tool_start",
                                MAPPER.writeValueAsString(Map.of(
                                        "toolName", te.toolName(),
                                        "toolCallId", te.toolCallId()))));
                    } else if (event instanceof ToolExecutionEndEvent te) {
                        sink.next(sse("tool_end",
                                MAPPER.writeValueAsString(Map.of(
                                        "toolCallId", te.toolCallId()))));
                    } else if (event instanceof AgentEndEvent) {
                        sink.next(sse("done", "{}"));
                        sink.complete();
                    }
                } catch (Exception e) {
                    log.warn("Failed to serialize SSE event", e);
                }
            });

            sink.onDispose(unsub::run);

            session.prompt(req.message()).whenComplete((v, ex) -> {
                if (ex != null) {
                    try {
                        sink.next(sse("error",
                                MAPPER.writeValueAsString(Map.of("error",
                                        ex.getMessage() != null ? ex.getMessage() : "Unknown error"))));
                    } catch (Exception ignored) {}
                    sink.complete();
                }
            });
        });

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(events, ServerSentEvent.class);
    }

    private static ServerSentEvent<String> sse(String eventType, String data) {
        return ServerSentEvent.<String>builder()
                .event(eventType)
                .data(data)
                .build();
    }
}
