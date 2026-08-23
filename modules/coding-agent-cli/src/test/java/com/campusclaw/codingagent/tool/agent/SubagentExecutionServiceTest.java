/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.agent.Agent;
import com.campusclaw.agent.state.AgentState;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.ManagedAgentSession;
import com.campusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SubagentExecutionServiceTest {

    private static final String PARENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String CHILD_ID = "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private AgentSessionFactory sessionFactory;

    private ModelCatalogService modelCatalogService;

    private Model model;

    private SubagentExecutionService service;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(AgentSessionFactory.class);
        model = model();
        modelCatalogService = mock(ModelCatalogService.class);
        when(modelCatalogService.getAvailableModels()).thenReturn(List.of(model));
        service = new SubagentExecutionService(sessionFactory, modelCatalogService);
    }

    @Test
    void executesDirectFixedVersionChildWithCommonChildProfile() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        PreparedAgentRuntime child = childRuntime("1.0.0", true);
        ManagedAgentSession session = completedSession("child answer");
        useRuntime(child, session);
        var updates = new java.util.ArrayList<String>();

        var result = service.execute(
                parent,
                SubagentExecutionContext.root(PARENT_ID, model, ThinkingLevel.MEDIUM),
                "researcher",
                "research task",
                new CancellationToken(),
                update -> updates.add(((TextContent) update.content().getFirst()).text()));

        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("child answer");
        assertThat(updates).containsExactly("Child Agent started: researcher", "Child Agent completed");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        assertThat(request.getValue().entryPoint()).isEqualTo(ToolEntryPoint.CHILD_AGENT);
        assertThat(request.getValue().agentId()).isEqualTo(CHILD_ID);
        verify(session).close();
    }

    @Test
    void rejectsVersionMismatchWithoutImplicitRefresh() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        useRuntime(childRuntime("2.0.0", true), completedSession("unused"));

        assertThatThrownBy(() -> execute(parent, rootContext(), new CancellationToken()))
                .hasMessage("Child Agent version does not match its binding");
    }

    @Test
    void fallsBackToAllowedInheritedModelWhenChildDefaultIsUnavailable() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        useRuntime(childRuntime("1.0.0", true), completedSession("child answer"));
        when(modelCatalogService.getAvailableModels()).thenReturn(List.of());

        var result = execute(parent, rootContext(), new CancellationToken());

        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("child answer");
    }

    @Test
    void rejectsDepthCycleAndDisabledChild() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        var atLimit = new SubagentExecutionContext(1, Set.of(PARENT_ID), model, ThinkingLevel.OFF);
        assertThatThrownBy(() -> execute(parent, atLimit, new CancellationToken()))
                .hasMessage("Child Agent depth limit exceeded");

        var cycle = new SubagentExecutionContext(0, Set.of(PARENT_ID, CHILD_ID), model, ThinkingLevel.OFF);
        assertThatThrownBy(() -> execute(parent, cycle, new CancellationToken()))
                .hasMessage("Child Agent path contains a cycle");

        useRuntime(childRuntime("1.0.0", false), completedSession("unused"));
        assertThatThrownBy(() -> execute(parent, rootContext(), new CancellationToken()))
                .hasMessage("Child Agent is disabled");
    }

    @Test
    void rejectsDisabledAndSelfBindingsBeforePreparingChild() {
        AgentReference disabled =
                new AgentReference(CHILD_ID, "researcher", "Researcher", "Researches", "1.0.0", false);
        assertThatThrownBy(() -> execute(parentRuntime(disabled), rootContext(), new CancellationToken()))
                .hasMessage("Child Agent binding is disabled");

        AgentReference self = new AgentReference(PARENT_ID, "researcher", "Researcher", "Researches", "1.0.0", true);
        assertThatThrownBy(() -> execute(parentRuntime(self), rootContext(), new CancellationToken()))
                .hasMessage("Child Agent must not reference the current Agent");
        verify(sessionFactory, never()).create(any());
    }

    @Test
    void propagatesCancellationBeforePrompt() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        ManagedAgentSession session = completedSession("unused");
        CancellationToken token = new CancellationToken();
        token.cancel();

        assertThatThrownBy(() -> execute(parent, rootContext(), token))
                .hasMessage("Child Agent execution was cancelled");
        verify(sessionFactory, never()).create(any());
        verify(session.agent(), never()).prompt(any(String.class));
    }

    private com.campusclaw.agent.tool.AgentToolResult execute(
            PreparedAgentRuntime parent, SubagentExecutionContext context, CancellationToken token) {
        return service.execute(parent, context, "researcher", "task", token, ignored -> {});
    }

    private SubagentExecutionContext rootContext() {
        return SubagentExecutionContext.root(PARENT_ID, model, ThinkingLevel.OFF);
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
        when(agent.subscribe(any())).thenReturn(() -> {});
        when(agent.prompt(any(String.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(agent.getState()).thenReturn(state);
        return session;
    }

    private void useRuntime(PreparedAgentRuntime runtime, ManagedAgentSession session) {
        when(sessionFactory.create(any())).thenAnswer(invocation -> {
            ManagedAgentSessionRequest request = invocation.getArgument(0);
            request.runtimeValidator().accept(runtime);
            request.modelResolver().apply(runtime);
            return session;
        });
    }

    private PreparedAgentRuntime parentRuntime(String childVersion) {
        AgentReference child =
                new AgentReference(CHILD_ID, "researcher", "Researcher", "Researches tasks", childVersion);
        return parentRuntime(child);
    }

    private PreparedAgentRuntime parentRuntime(AgentReference child) {
        AgentRuntime runtime = runtime(PARENT_ID, "parent", "1.0.0", true, List.of(child));
        return new PreparedAgentRuntime(PARENT_ID, Path.of("agent", PARENT_ID), runtime, List.of());
    }

    private PreparedAgentRuntime childRuntime(String version, boolean enabled) {
        AgentRuntime runtime = runtime(CHILD_ID, "researcher", version, enabled, List.of());
        return new PreparedAgentRuntime(CHILD_ID, Path.of("agent", CHILD_ID), runtime, List.of());
    }

    private AgentRuntime runtime(
            String id, String name, String version, boolean enabled, List<AgentReference> children) {
        return new AgentRuntime(
                List.of(model.id()),
                List.of(),
                List.of(),
                children,
                List.of(),
                name,
                enabled,
                id,
                name,
                "prompt",
                List.of(),
                version);
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
