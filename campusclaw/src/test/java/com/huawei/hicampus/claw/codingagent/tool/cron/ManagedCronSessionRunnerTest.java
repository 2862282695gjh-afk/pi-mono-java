/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.agent.state.AgentState;
import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.InputModality;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ModelCost;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;

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

    private ModelCatalogService modelCatalogService;

    private ManagedCronSessionRunner runner;

    private Model model;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(AgentSessionFactory.class);
        modelCatalogService = mock(ModelCatalogService.class);
        model = model();
        runner =
                new ManagedCronSessionRunner(sessionFactory, modelCatalogService, mock(SubagentExecutionService.class));
    }

    @Test
    void preparesAgentAndUsesCronProfileAtTriggerTime() {
        when(modelCatalogService.getAvailableModels()).thenReturn(List.of(model));
        ManagedAgentSession session = completedSession("cron answer");
        when(sessionFactory.create(any())).thenReturn(session);

        assertThat(runner.execute(AGENT_ID, "scheduled task")).isEqualTo("cron answer");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        assertThat(request.getValue().entryPoint()).isEqualTo(ToolEntryPoint.CRON);
        assertThat(request.getValue().agentId()).isEqualTo(AGENT_ID);
        assertThat(request.getValue().mateCredentials().isComplete()).isFalse();
        assertThat(request.getValue().modelResolver().apply(runtime())).isSameAs(model);
    }

    @Test
    void rejectsDefaultModelWithoutUsableCredentials() {
        when(modelCatalogService.getAvailableModels()).thenReturn(List.of());
        ManagedAgentSession session = completedSession("unused");
        when(sessionFactory.create(any())).thenReturn(session);

        runner.execute(AGENT_ID, "scheduled task");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        assertThatThrownBy(() -> request.getValue().modelResolver().apply(runtime()))
                .hasMessage("Cron Agent default model is unavailable");
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
        AgentRuntime metadata = new AgentRuntime(
                List.of(model.id()),
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
}
