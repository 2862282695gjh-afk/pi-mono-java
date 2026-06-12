/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Synchronous stdio JSON-RPC MCP transport.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class StdioMcpTransport implements McpTransport {

    private final ObjectMapper mapper;
    private final AtomicLong nextId = new AtomicLong(1L);
    private final Process process;
    private final BufferedReader reader;
    private final OutputStreamWriter writer;

    public StdioMcpTransport(ObjectMapper mapper, McpServerConfig config) {
        this.mapper = mapper;
        try {
            var processBuilder = new ProcessBuilder(config.command());
            processBuilder.environment().clear();
            processBuilder.environment().putAll(config.env());
            this.process = processBuilder.start();
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McpException("failed to start MCP stdio server: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized JsonNode request(String method, JsonNode params) {
        long id = nextId.getAndIncrement();
        try {
            var envelope = mapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", id);
            envelope.put("method", method);
            envelope.set("params", params);
            writer.write(mapper.writeValueAsString(envelope));
            writer.write('\n');
            writer.flush();
            return readMatchingResult(id);
        } catch (IOException e) {
            throw new McpException("MCP stdio request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }

    private JsonNode readMatchingResult(long id) throws IOException {
        long deadline = System.nanoTime() + Duration.ofMinutes(5L).toNanos();
        while (System.nanoTime() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                throw new McpException("MCP stdio server closed");
            }
            JsonNode envelope = mapper.readTree(line);
            if (envelope.path("id").asLong(-1L) != id) {
                continue;
            }
            if (envelope.has("error")) {
                throw new McpException(envelope.path("error").path("message").asText("MCP error"));
            }
            return envelope.path("result");
        }
        throw new McpException("MCP stdio request timed out");
    }
}
