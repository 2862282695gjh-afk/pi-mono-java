/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Simple JSON-RPC-over-HTTP MCP transport.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class HttpMcpTransport implements McpTransport {

    private final ObjectMapper mapper;
    private final McpServerConfig config;
    private final HttpClient client;
    private final AtomicLong nextId = new AtomicLong(1L);

    public HttpMcpTransport(ObjectMapper mapper, McpServerConfig config) {
        this.mapper = mapper;
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.startupTimeoutSeconds()))
                .build();
    }

    @Override
    public JsonNode request(String method, JsonNode params) {
        try {
            var envelope = mapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", nextId.getAndIncrement());
            envelope.put("method", method);
            envelope.set("params", params);
            var request = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(Duration.ofSeconds(config.callTimeoutSeconds()))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(envelope)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new McpException("MCP HTTP request failed with status " + response.statusCode());
            }
            return readResult(mapper.readTree(response.body()));
        } catch (IOException e) {
            throw new McpException("MCP HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP HTTP request interrupted", e);
        }
    }

    @Override
    public void close() {}

    private JsonNode readResult(JsonNode envelope) {
        if (envelope.has("error")) {
            throw new McpException(envelope.path("error").path("message").asText("MCP error"));
        }
        return envelope.path("result");
    }
}
