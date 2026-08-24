/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Api;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class AgentSessionFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsIsolatedCommonSessionsWithConfiguredEntryPoint() throws Exception {
        Path agentRoot = Files.createDirectory(temporaryDirectory.resolve("agent-a"));
        Files.createDirectory(agentRoot.resolve(".campusclaw"));
        PreparedAgentRuntime runtime = prepared(agentRoot);
        ConfiguredToolAssembler assembler = mock(ConfiguredToolAssembler.class);
        AtomicInteger sequence = new AtomicInteger();
        when(assembler.assemble(eq(ToolEntryPoint.RUNTIME), any()))
                .thenAnswer(invocation -> List.of(new StubTool("Read-" + sequence.incrementAndGet())));
        RuntimeAgentPromptLoader promptLoader = mock(RuntimeAgentPromptLoader.class);
        when(promptLoader.load(agentRoot.resolve(".campusclaw"))).thenReturn("system prompt");
        @SuppressWarnings("unchecked")
        ObjectProvider<MateToolsetFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        AgentRuntimeManager runtimeManager = mock(AgentRuntimeManager.class);
        when(runtimeManager.prepare(runtime.agentId())).thenReturn(runtime);
        AgentSessionFactory factory = new AgentSessionFactory(
                mock(CampusClawAiService.class),
                runtimeManager,
                assembler,
                provider,
                promptLoader,
                mock(com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactor.class));
        ManagedAgentSessionRequest request = ManagedAgentSessionRequest.create(
                runtime.agentId(), ToolEntryPoint.RUNTIME, model(), ThinkingLevel.MEDIUM);

        ManagedAgentSession first = factory.create(request);
        ManagedAgentSession second = factory.create(request);

        assertThat(first).isNotSameAs(second);
        assertThat(first.agent()).isNotSameAs(second.agent());
        assertThat(first.tools().getFirst()).isNotSameAs(second.tools().getFirst());
        assertThat(first.agent().getState().getSystemPrompt()).isEqualTo("system prompt");
        assertThat(first.agent().getState().getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        verify(assembler, org.mockito.Mockito.times(2)).assemble(eq(ToolEntryPoint.RUNTIME), any());
        verify(runtimeManager, org.mockito.Mockito.times(2)).prepare(runtime.agentId());
    }

    private PreparedAgentRuntime prepared(Path agentRoot) {
        String id = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                id,
                "agent-a",
                "prompt",
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(id, agentRoot, metadata, List.of());
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

        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return name;
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
