/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class JsonRpcMcpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void initializesTransportBeforeListingTools() {
        var transport = new RecordingTransport();
        var client = new JsonRpcMcpClient(mapper, transport);

        var tools = client.listTools();

        assertThat(tools).extracting(McpToolDefinition::name).containsExactly("echo");
        assertThat(transport.calls)
                .containsExactly("request:initialize", "notify:notifications/initialized", "request:tools/list");
    }

    @Test
    void initializesTransportOnlyOnceForSubsequentCalls() {
        var transport = new RecordingTransport();
        var client = new JsonRpcMcpClient(mapper, transport);

        client.listTools();
        client.callTool("echo", Map.of("message", "hi"), new CancellationToken());

        assertThat(transport.calls)
                .containsExactly(
                        "request:initialize",
                        "notify:notifications/initialized",
                        "request:tools/list",
                        "request:tools/call");
    }

    private final class RecordingTransport implements McpTransport {

        private final List<String> calls = new ArrayList<>();

        @Override
        public JsonNode request(String method, JsonNode params) {
            calls.add("request:" + method);
            if ("initialize".equals(method)) {
                return mapper.createObjectNode()
                        .put("protocolVersion", "2025-03-26")
                        .set("capabilities", mapper.createObjectNode().set("tools", mapper.createObjectNode()));
            }
            if ("tools/list".equals(method)) {
                var result = mapper.createObjectNode();
                var tools = mapper.createArrayNode();
                tools.add(mapper.createObjectNode()
                        .put("name", "echo")
                        .put("description", "Echo")
                        .set("inputSchema", mapper.createObjectNode().put("type", "object")));
                result.set("tools", tools);
                return result;
            }
            return mapper.createObjectNode();
        }

        @Override
        public JsonNode request(String method, JsonNode params, CancellationToken signal) {
            calls.add("request:" + method);
            var content = mapper.createArrayNode()
                    .add(mapper.createObjectNode().put("type", "text").put("text", "ok"));
            return mapper.createObjectNode().set("content", content);
        }

        @Override
        public void notify(String method, JsonNode params) {
            calls.add("notify:" + method);
        }

        @Override
        public void close() {}
    }
}
