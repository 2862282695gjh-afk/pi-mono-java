/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.campusclaw.agent.tool.CancellationToken;
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
    private final int callTimeoutSeconds;

    public StdioMcpTransport(ObjectMapper mapper, McpServerConfig config) {
        this.mapper = mapper;
        this.callTimeoutSeconds = config.callTimeoutSeconds() > 0 ? config.callTimeoutSeconds() : 60;
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
        return request(method, params, null);
    }

    @Override
    public synchronized JsonNode request(String method, JsonNode params, CancellationToken signal) {
        if (signal != null && signal.isCancelled()) {
            throw new McpException("MCP stdio request cancelled");
        }
        var cancelled = new AtomicBoolean(false);
        if (signal != null) {
            signal.onCancel(() -> {
                cancelled.set(true);
                process.destroyForcibly();
            });
        }
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
            return readMatchingResultWithTimeout(id, cancelled);
        } catch (IOException e) {
            if (cancelled.get()) {
                throw new McpException("MCP stdio request cancelled", e);
            }
            throw new McpException("MCP stdio request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }

    private JsonNode readMatchingResultWithTimeout(long id, AtomicBoolean cancelled) {
        try (var executor =
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var future = executor.submit(() -> readMatchingResult(id));
            return future.get(callTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw new McpException("MCP stdio request timed out after " + callTimeoutSeconds + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP stdio request interrupted", e);
        } catch (ExecutionException e) {
            if (cancelled.get()) {
                throw new McpException("MCP stdio request cancelled", e);
            }
            var cause = e.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException("MCP stdio request failed: " + cause.getMessage(), cause);
        }
    }

    private JsonNode readMatchingResult(long id) throws IOException {
        while (true) {
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
    }
}
