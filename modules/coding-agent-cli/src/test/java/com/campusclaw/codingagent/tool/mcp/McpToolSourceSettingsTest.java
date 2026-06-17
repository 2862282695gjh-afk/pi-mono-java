/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.settings.Settings;
import com.campusclaw.codingagent.settings.SettingsManager;
import com.campusclaw.codingagent.tool.catalog.ToolSourceContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class McpToolSourceSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void loadsEnabledMcpServersFromSettings() {
        SettingsManager settingsManager = mock(SettingsManager.class);
        Settings.McpServerSettings server = new Settings.McpServerSettings(
                "stdio",
                "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
                null,
                true,
                "trusted",
                "fs_",
                "prefixed",
                Map.of("SAFE_ENV", "1"),
                3,
                4);
        when(settingsManager.load()).thenReturn(settingsWithTools(server));
        RecordingFactory factory = new RecordingFactory();
        var source = new McpToolSource(settingsManager, factory);

        var contributions = source.load(ToolSourceContext.defaults());

        assertThat(factory.config.name()).isEqualTo("filesystem");
        assertThat(factory.config.command())
                .containsExactly("npx", "-y", "@modelcontextprotocol/server-filesystem", "/workspace");
        assertThat(factory.config.env()).containsEntry("SAFE_ENV", "1");
        assertThat(contributions).extracting(c -> c.tool().name()).containsExactly("fs_echo");
    }

    private Settings settingsWithTools(Settings.McpServerSettings server) {
        return new Settings(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new Settings.ToolsSettings(null, null, null, null, Map.of("filesystem", server)));
    }

    private static final class RecordingFactory implements McpClientFactory {
        private McpServerConfig config;

        @Override
        public McpClient create(McpServerConfig config) {
            this.config = config;
            return new McpClient() {
                @Override
                public List<McpToolDefinition> listTools() {
                    return List.of(new McpToolDefinition(
                            "echo", "Echo", MAPPER.createObjectNode().put("type", "object")));
                }

                @Override
                public McpCallResult callTool(
                        String name,
                        Map<String, Object> arguments,
                        com.campusclaw.agent.tool.CancellationToken signal) {
                    return new McpCallResult(List.of(McpContent.text("ok")), null);
                }

                @Override
                public void close() {
                    // Test client has no resources.
                }
            };
        }
    }
}
