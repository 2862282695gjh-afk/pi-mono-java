package com.mariozechner.pi.ai.provider.google;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mariozechner.pi.ai.types.*;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for Google Generative AI and Vertex AI providers.
 * Handles message/tool conversion between the unified types and Google's API format.
 */
public final class GoogleShared {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoogleShared() {}

    /**
     * Converts unified messages to Google API content format.
     */
    public static ArrayNode convertMessages(List<Message> messages) {
        var contents = MAPPER.createArrayNode();
        for (var message : messages) {
            switch (message) {
                case UserMessage um -> {
                    var content = MAPPER.createObjectNode();
                    content.put("role", "user");
                    var parts = MAPPER.createArrayNode();
                    for (var block : um.content()) {
                        if (block instanceof TextContent tc) {
                            parts.add(MAPPER.createObjectNode().put("text", tc.text()));
                        }
                    }
                    content.set("parts", parts);
                    contents.add(content);
                }
                case AssistantMessage am -> {
                    var content = MAPPER.createObjectNode();
                    content.put("role", "model");
                    var parts = MAPPER.createArrayNode();
                    for (var block : am.content()) {
                        switch (block) {
                            case TextContent tc -> parts.add(MAPPER.createObjectNode().put("text", tc.text()));
                            case ToolCall tc -> {
                                var fnCall = MAPPER.createObjectNode();
                                var fc = MAPPER.createObjectNode();
                                fc.put("name", tc.name());
                                fc.set("args", MAPPER.valueToTree(tc.arguments()));
                                fnCall.set("functionCall", fc);
                                parts.add(fnCall);
                            }
                            default -> {} // Skip thinking, image etc.
                        }
                    }
                    content.set("parts", parts);
                    contents.add(content);
                }
                case ToolResultMessage trm -> {
                    var content = MAPPER.createObjectNode();
                    content.put("role", "user");
                    var parts = MAPPER.createArrayNode();
                    var fnResp = MAPPER.createObjectNode();
                    var fr = MAPPER.createObjectNode();
                    fr.put("name", trm.toolCallId());
                    var response = MAPPER.createObjectNode();
                    var sb = new StringBuilder();
                    for (var block : trm.content()) {
                        if (block instanceof TextContent tc) sb.append(tc.text());
                    }
                    response.put("result", sb.toString());
                    fr.set("response", response);
                    fnResp.set("functionResponse", fr);
                    parts.add(fnResp);
                    content.set("parts", parts);
                    contents.add(content);
                }
                default -> {} // Skip unknown message types
            }
        }
        return contents;
    }

    /**
     * Converts unified Tool definitions to Google function declarations.
     */
    public static ArrayNode convertTools(@Nullable List<Tool> tools) {
        if (tools == null || tools.isEmpty()) return null;
        var toolsArray = MAPPER.createArrayNode();
        var toolObj = MAPPER.createObjectNode();
        var functionDeclarations = MAPPER.createArrayNode();
        for (var tool : tools) {
            var fd = MAPPER.createObjectNode();
            fd.put("name", tool.name());
            fd.put("description", tool.description());
            if (tool.parameters() != null) {
                fd.set("parameters", MAPPER.valueToTree(tool.parameters()));
            }
            functionDeclarations.add(fd);
        }
        toolObj.set("functionDeclarations", functionDeclarations);
        toolsArray.add(toolObj);
        return toolsArray;
    }

    /**
     * Parses a streaming response chunk from Google's API.
     */
    public static ParsedChunk parseChunk(JsonNode chunk) {
        var candidates = chunk.path("candidates");
        if (candidates.isEmpty() || !candidates.isArray()) {
            return new ParsedChunk(List.of(), null, null);
        }
        var candidate = candidates.get(0);
        var content = candidate.path("content");
        var parts = content.path("parts");
        var blocks = new ArrayList<ContentBlock>();

        if (parts.isArray()) {
            for (var part : parts) {
                if (part.has("text")) {
                    blocks.add(new TextContent(part.get("text").asText()));
                } else if (part.has("functionCall")) {
                    var fc = part.get("functionCall");
                    String name = fc.get("name").asText();
                    Map<String, Object> args = Map.of();
                    if (fc.has("args")) {
                        try {
                            args = MAPPER.convertValue(fc.get("args"),
                                new TypeReference<>() {});
                        } catch (Exception ignored) {}
                    }
                    blocks.add(new ToolCall(java.util.UUID.randomUUID().toString(), name, args));
                }
            }
        }

        String finishReason = candidate.has("finishReason")
            ? candidate.get("finishReason").asText() : null;

        Usage usage = null;
        var usageNode = chunk.path("usageMetadata");
        if (!usageNode.isMissingNode()) {
            int inputTokens = usageNode.path("promptTokenCount").asInt(0);
            int outputTokens = usageNode.path("candidatesTokenCount").asInt(0);
            usage = new Usage(inputTokens, outputTokens, 0, 0, inputTokens + outputTokens, Cost.empty());
        }

        return new ParsedChunk(blocks, finishReason, usage);
    }

    /**
     * Maps Google finish reason to unified StopReason.
     */
    public static StopReason mapFinishReason(@Nullable String reason) {
        if (reason == null) return StopReason.STOP;
        return switch (reason) {
            case "STOP" -> StopReason.STOP;
            case "MAX_TOKENS" -> StopReason.LENGTH;
            case "SAFETY", "RECITATION", "OTHER" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }

    /**
     * Parsed result from a Google API streaming chunk.
     */
    public record ParsedChunk(List<ContentBlock> blocks, @Nullable String finishReason, @Nullable Usage usage) {}
}
