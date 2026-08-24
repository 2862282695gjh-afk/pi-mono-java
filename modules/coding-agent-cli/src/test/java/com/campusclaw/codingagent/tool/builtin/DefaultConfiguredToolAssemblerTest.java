/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class DefaultConfiguredToolAssemblerTest {

    @Test
    void createsNewToolsInConfiguredOrderForEverySession(@org.junit.jupiter.api.io.TempDir Path agentRoot)
            throws Exception {
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of("Grep", "Read"));
        properties.afterPropertiesSet();
        var assembler = new DefaultConfiguredToolAssembler(properties, StubTool::new);
        PreparedAgentRuntime runtime = prepared(agentRoot);
        var context = new ToolAssemblyContext(
                ToolEntryPoint.RUNTIME,
                runtime,
                model(),
                ThinkingLevel.OFF,
                AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot),
                Map.of(),
                List.of(),
                null,
                null,
                null);

        List<AgentTool> first = assembler.assemble(ToolEntryPoint.RUNTIME, context);
        List<AgentTool> second = assembler.assemble(ToolEntryPoint.RUNTIME, context);

        assertThat(first).extracting(AgentTool::name).containsExactly("Grep", "Read");
        assertThat(second).extracting(AgentTool::name).containsExactly("Grep", "Read");
        assertThat(first.getFirst()).isNotSameAs(second.getFirst());
        assertThat(first.getLast()).isNotSameAs(second.getLast());
    }

    private static PreparedAgentRuntime prepared(Path agentRoot) {
        String agentId = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                agentId,
                "agent-a",
                "prompt",
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(agentId, agentRoot, metadata, List.of());
    }

    private static Model model() {
        return new Model(
                "model-a",
                "Model A",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                1000,
                100,
                null,
                null,
                null);
    }

    private static final class StubTool implements AgentTool {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final BuiltInToolName name;

        private StubTool(BuiltInToolName name, ToolAssemblyContext context) {
            this.name = name;
        }

        @Override
        public String name() {
            return name.externalName();
        }

        @Override
        public String label() {
            return name();
        }

        @Override
        public String description() {
            return name();
        }

        @Override
        public JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            return new AgentToolResult(List.of(), null);
        }
    }
}
