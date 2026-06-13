/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class McpAgentToolTest {

    @Test
    void callsMcpToolWithArgumentsAndMapsTextContent() throws Exception {
        var seenArguments = new AtomicReference<Map<String, Object>>();
        var client = new RecordingMcpClient(seenArguments, false);
        var definition = new McpToolDefinition(
                "echo", "Echo input", new ObjectMapper().createObjectNode().put("type", "object"));
        var tool = new McpAgentTool("server__echo", "server echo", definition, client);

        var result = tool.execute("call-1", Map.of("message", "hello"), new CancellationToken(), partial -> {});

        assertThat(seenArguments.get()).isEqualTo(Map.of("message", "hello"));
        assertThat(result.content()).hasSize(1);
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("hello from mcp");
        assertThat(result.details()).isEqualTo(Map.of("structured", Map.of("ok", true)));
    }

    @Test
    void mapsMcpErrorsToToolExceptions() {
        var definition = new McpToolDefinition(
                "echo", "Echo input", new ObjectMapper().createObjectNode().put("type", "object"));
        var tool = new McpAgentTool("server__echo", "server echo", definition, new RecordingMcpClient(null, true));

        assertThatThrownBy(() -> tool.execute("call-1", Map.of(), new CancellationToken(), partial -> {}))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("tool failed");
    }

    private record RecordingMcpClient(AtomicReference<Map<String, Object>> seenArguments, boolean fail)
            implements McpClient {

        @Override
        public List<McpToolDefinition> listTools() {
            return List.of();
        }

        @Override
        public McpCallResult callTool(String name, Map<String, Object> arguments, CancellationToken signal) {
            if (fail) {
                throw new McpException("tool failed");
            }
            if (seenArguments != null) {
                seenArguments.set(arguments);
            }
            return new McpCallResult(
                    List.of(McpContent.text("hello from mcp")), Map.of("structured", Map.of("ok", true)));
        }

        @Override
        public void close() {}
    }
}
