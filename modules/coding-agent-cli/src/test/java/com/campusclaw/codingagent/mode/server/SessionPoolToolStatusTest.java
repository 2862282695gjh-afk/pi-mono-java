/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.mode.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.session.SessionConfig;
import com.campusclaw.codingagent.tool.catalog.DefaultToolCatalog;
import com.campusclaw.codingagent.tool.catalog.SpringAgentToolSource;
import com.campusclaw.codingagent.tool.catalog.ToolSelection;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SessionPoolToolStatusTest {

    @Test
    void toolStatusIncludesCatalogSnapshotAndVisibleTools() {
        var read = new TestTool("read");
        var bash = new TestTool("bash");
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(read, bash))));
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read, bash),
                catalog,
                new ToolSelection(List.of("read"), List.of(), false),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                null,
                false,
                false);

        Map<String, Object> status = pool.toolStatus();

        assertThat(status).containsEntry("status", "ok");
        assertThat(status).containsEntry("activeSessions", 0);
        assertThat(status).containsEntry("version", catalog.snapshot().version());
        assertThat(status.get("tools")).asList().containsExactly("read");
        assertThat(status.get("diagnostics")).asList().isEmpty();
    }

    private record TestTool(String name) implements AgentTool {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return "Test tool " + name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
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
