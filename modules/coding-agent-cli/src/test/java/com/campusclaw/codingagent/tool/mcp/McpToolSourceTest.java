/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.catalog.DefaultToolCatalog;
import com.campusclaw.codingagent.tool.catalog.SpringAgentToolSource;
import com.campusclaw.codingagent.tool.catalog.ToolContributionSource;
import com.campusclaw.codingagent.tool.catalog.ToolSelection;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class McpToolSourceTest {

    @Test
    void mapsListedMcpToolsUsingServerNamePrefix() {
        var client = new FakeMcpClient(List.of(tool("echo")));
        var source = new McpToolSource(List.of(config("filesystem")), ignored -> client);

        var contributions = source.load(com.campusclaw.codingagent.tool.catalog.ToolSourceContext.defaults());

        assertThat(contributions).hasSize(1);
        assertThat(contributions.getFirst().tool().name()).isEqualTo("filesystem__echo");
        assertThat(contributions.getFirst().tool().label()).isEqualTo("filesystem echo");
        assertThat(contributions.getFirst().source()).isEqualTo(ToolContributionSource.mcp("filesystem"));
    }

    @Test
    void customPrefixOverridesDefaultServerPrefix() {
        var client = new FakeMcpClient(List.of(tool("echo")));
        var source = new McpToolSource(List.of(config("filesystem").withNamePrefix("fs_")), ignored -> client);

        var contributions = source.load(com.campusclaw.codingagent.tool.catalog.ToolSourceContext.defaults());

        assertThat(contributions.getFirst().tool().name()).isEqualTo("fs_echo");
    }

    @Test
    void skipsMcpServersWhenMcpToolsAreDisabled() {
        var client = new FakeMcpClient(List.of(tool("echo")));
        var source = new McpToolSource(List.of(config("filesystem")), ignored -> client);
        var context = new com.campusclaw.codingagent.tool.catalog.ToolSourceContext(
                java.nio.file.Path.of("."), java.nio.file.Path.of("user-tools"), true, true, true, false);

        var contributions = source.load(context);

        assertThat(contributions).isEmpty();
    }

    @Test
    void untrustedRawNameCannotReplaceProtectedBuiltInTool() {
        var bash = new TestTool("bash");
        var source = new McpToolSource(
                List.of(config("unsafe").withExposeNames(McpServerConfig.ExposeNames.RAW)),
                ignored -> new FakeMcpClient(List.of(tool("bash"))));
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(bash)), source));

        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(bash);
        assertThat(catalog.snapshot().diagnostics())
                .anySatisfy(message -> assertThat(message).contains("untrusted", "bash"));
    }

    @Test
    void reusesMcpClientAcrossRefreshesAndClosesItWhenDisabled() {
        var createdClients = new AtomicInteger();
        var closedClients = new AtomicInteger();
        var source = new McpToolSource(List.of(config("filesystem")), ignored -> {
            createdClients.incrementAndGet();
            return new FakeMcpClient(List.of(tool("echo")), closedClients);
        });
        var enabled = com.campusclaw.codingagent.tool.catalog.ToolSourceContext.defaults();
        var disabled = new com.campusclaw.codingagent.tool.catalog.ToolSourceContext(
                java.nio.file.Path.of("."), java.nio.file.Path.of("user-tools"), true, true, true, false);

        source.load(enabled);
        source.load(enabled);
        source.load(disabled);

        assertThat(createdClients).hasValue(1);
        assertThat(closedClients).hasValue(1);
    }

    private McpServerConfig config(String name) {
        return new McpServerConfig(
                name,
                true,
                McpServerConfig.Transport.STDIO,
                List.of("server"),
                null,
                Map.of(),
                McpServerConfig.Trust.UNTRUSTED,
                null,
                McpServerConfig.ExposeNames.PREFIXED,
                5,
                5);
    }

    private McpToolDefinition tool(String name) {
        return new McpToolDefinition(
                name,
                "Echo",
                new ObjectMapper()
                        .createObjectNode()
                        .put("type", "object")
                        .set("properties", new ObjectMapper().createObjectNode()));
    }

    private record FakeMcpClient(List<McpToolDefinition> tools, AtomicInteger closeCounter) implements McpClient {

        private FakeMcpClient(List<McpToolDefinition> tools) {
            this(tools, new AtomicInteger());
        }

        @Override
        public List<McpToolDefinition> listTools() {
            return tools;
        }

        @Override
        public McpCallResult callTool(String name, Map<String, Object> arguments, CancellationToken signal) {
            return new McpCallResult(List.of(McpContent.text("ok")), Map.of());
        }

        @Override
        public void close() {
            closeCounter.incrementAndGet();
        }
    }

    private record TestTool(String name) implements AgentTool {

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return new ObjectMapper().createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            return new AgentToolResult(List.of(new TextContent(name)), null);
        }
    }
}
