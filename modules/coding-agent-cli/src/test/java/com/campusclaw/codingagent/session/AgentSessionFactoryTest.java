/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

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

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.mate.MateToolSessionState;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;
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
        MateToolsetFactory mateToolsetFactory = mock(MateToolsetFactory.class);
        when(provider.getIfAvailable()).thenReturn(mateToolsetFactory);
        AgentRuntimeManager runtimeManager = mock(AgentRuntimeManager.class);
        when(runtimeManager.prepare(runtime.agentId())).thenReturn(runtime);
        AgentSessionFactory factory = new AgentSessionFactory(
                mock(CampusClawAiService.class),
                runtimeManager,
                assembler,
                provider,
                promptLoader,
                mock(com.campusclaw.codingagent.session.compaction.SessionCompactor.class));
        MateCredentials credentials = MateCredentials.jwt("caller-1", "token-1", "access-token-1");
        when(mateToolsetFactory.createSession(runtime.agentId(), Map.of(), credentials))
                .thenReturn(mock(MateToolSessionState.class));
        ManagedAgentSessionRequest request = new ManagedAgentSessionRequest(
                runtime.agentId(),
                ToolEntryPoint.RUNTIME,
                ignored -> model(),
                ThinkingLevel.MEDIUM,
                credentials,
                null,
                null,
                null,
                List.of(),
                List.of());

        ManagedAgentSession first = factory.create(request);
        ManagedAgentSession second = factory.create(request);

        assertThat(first).isNotSameAs(second);
        assertThat(first.agent()).isNotSameAs(second.agent());
        assertThat(first.tools().getFirst()).isNotSameAs(second.tools().getFirst());
        assertThat(first.agent().getState().getSystemPrompt()).isEqualTo("system prompt");
        assertThat(first.agent().getState().getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        verify(assembler, org.mockito.Mockito.times(2)).assemble(eq(ToolEntryPoint.RUNTIME), any());
        verify(runtimeManager, org.mockito.Mockito.times(2)).prepare(runtime.agentId());
        verify(mateToolsetFactory, org.mockito.Mockito.times(2))
                .createSession(runtime.agentId(), Map.of(), credentials);
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
