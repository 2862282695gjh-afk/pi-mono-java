/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.agent.Agent;
import com.campusclaw.agent.state.AgentState;
import com.campusclaw.ai.env.ProviderConfigResolver;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.model.MateModelManagerProperties;
import com.campusclaw.codingagent.runtimeapi.model.MateRuntimeModelManager;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.ManagedAgentSession;
import com.campusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link ManagedCronSessionRunner} 的目录准备、模型可用性和公共 Session 装配测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class ManagedCronSessionRunnerTest {

    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private AgentSessionFactory sessionFactory;

    private ModelCatalogService emptyModelCatalog;

    private ManagedCronSessionRunner runner;

    private Model model;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(AgentSessionFactory.class);
        emptyModelCatalog = new ModelCatalogService(new ModelRegistry(), mock(ProviderConfigResolver.class));
        model = model();
        runner = new ManagedCronSessionRunner(
                sessionFactory,
                new MateRuntimeModelManager(modelManagerProperties()),
                mock(SubagentExecutionService.class));
    }

    @Test
    void preparesAgentAndUsesCronProfileAtTriggerTime() {
        ManagedAgentSession session = completedSession("cron answer");
        when(sessionFactory.create(any())).thenReturn(session);

        assertThat(runner.execute(AGENT_ID, "scheduled task")).isEqualTo("cron answer");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        assertThat(request.getValue().entryPoint()).isEqualTo(ToolEntryPoint.CRON);
        assertThat(request.getValue().agentId()).isEqualTo(AGENT_ID);
        assertThat(request.getValue().mateCredentials().isComplete()).isFalse();
        assertThat(emptyModelCatalog.getAvailableModels()).isEmpty();
        Model selected = request.getValue().modelResolver().apply(runtime());
        assertThat(selected.id()).isEqualTo("model-a");
        assertThat(selected.provider()).isEqualTo(Provider.CAMPUSMATE);
    }

    @Test
    void rejectsAgentWithoutDefaultModel() {
        ManagedAgentSession session = completedSession("unused");
        when(sessionFactory.create(any())).thenReturn(session);

        runner.execute(AGENT_ID, "scheduled task");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        assertThatThrownBy(() -> request.getValue().modelResolver().apply(runtime(List.of())))
                .hasMessage("Cron Agent has no default model");
    }

    @Test
    void doesNotNormalizeLegacyProviderTextToCurrentCampusMateIdentity() {
        ManagedAgentSession session = completedSession("unused");
        when(sessionFactory.create(any())).thenReturn(session);

        runner.execute(AGENT_ID, "scheduled task");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());

        Model selected = request.getValue().modelResolver().apply(runtime("mate-model-manager/model-a"));
        assertThat(selected.id()).isEqualTo("mate-model-manager/model-a");
        assertThat(selected.id()).isNotEqualTo("model-a");
        assertThat(selected.provider()).isEqualTo(Provider.CAMPUSMATE);
    }

    private ManagedAgentSession completedSession(String answer) {
        ManagedAgentSession session = mock(ManagedAgentSession.class);
        Agent agent = mock(Agent.class);
        AgentState state = new AgentState();
        state.setMessages(List.of(new AssistantMessage(
                List.of(new TextContent(answer)),
                model.api().value(),
                model.provider().value(),
                model.id(),
                null,
                Usage.empty(),
                StopReason.STOP,
                null,
                1)));
        when(session.agent()).thenReturn(agent);
        when(session.prompt(any(String.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(agent.getState()).thenReturn(state);
        return session;
    }

    private PreparedAgentRuntime runtime() {
        return runtime(model.id());
    }

    private PreparedAgentRuntime runtime(String configuredModel) {
        return runtime(List.of(configuredModel));
    }

    private PreparedAgentRuntime runtime(List<String> configuredModels) {
        AgentRuntime metadata = new AgentRuntime(
                configuredModels,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "cron-agent",
                true,
                AGENT_ID,
                "cron-agent",
                "prompt",
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(AGENT_ID, Path.of("agent", AGENT_ID), metadata, List.of());
    }

    private static Model model() {
        return new Model(
                "model-a",
                "Model A",
                Api.OPENAI_COMPLETIONS,
                Provider.CAMPUSMATE,
                null,
                true,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                1000,
                100,
                null,
                null,
                null);
    }

    private static MateModelManagerProperties modelManagerProperties() {
        MateModelManagerProperties properties = new MateModelManagerProperties();
        properties.setContextWindow(1_000);
        properties.setMaxOutputTokens(100);
        return properties;
    }
}
