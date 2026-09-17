/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.agent;

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

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.agent.state.AgentState;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.ai.env.ProviderConfigResolver;
import com.huawei.hicampus.claw.ai.model.ModelRegistry;
import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.InputModality;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ModelCost;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentReference;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.MateModelManagerProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.MateRuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SubagentExecutionServiceTest {

    private static final String PARENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String CHILD_ID = "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private AgentSessionFactory sessionFactory;

    private ModelCatalogService emptyModelCatalog;

    private RuntimeModelManager modelManager;

    private Model model;

    private SubagentExecutionService service;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(AgentSessionFactory.class);
        model = model();
        emptyModelCatalog = new ModelCatalogService(new ModelRegistry(), mock(ProviderConfigResolver.class));
        modelManager = new MateRuntimeModelManager(modelManagerProperties());
        service = new SubagentExecutionService(sessionFactory, modelManager);
    }

    @Test
    void executesDirectFixedVersionChildWithCommonChildProfile() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        PreparedAgentRuntime child = childRuntime("1.0.0", true);
        ManagedAgentSession session = completedSession("child answer");
        useRuntime(child, session);
        var updates = new java.util.ArrayList<String>();
        MateCredentials credentials = MateCredentials.jwt("caller-1", "token-1", "access-token-1");

        var result = service.execute(
                parent,
                SubagentExecutionContext.root(PARENT_ID, model, ThinkingLevel.MEDIUM, credentials),
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
        assertThat(request.getValue().mateCredentials()).isSameAs(credentials);
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
    void resolvesIndependentManagedDefaultWhenGlobalRegistryIsEmpty() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        PreparedAgentRuntime child = childRuntime("1.0.0", true, List.of("team/model-b"));
        useRuntime(child, completedSession("child answer"));

        var result = execute(parent, rootContext(), new CancellationToken());

        assertThat(emptyModelCatalog.getAvailableModels()).isEmpty();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("child answer");
        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        Model selected = request.getValue().modelResolver().apply(child);
        assertThat(selected.id()).isEqualTo("team/model-b");
        assertThat(selected.provider()).isEqualTo(Provider.CAMPUSMATE);
    }

    @Test
    void preservesLegacyQualifiedTextAsOpaqueManagedModelId() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        PreparedAgentRuntime child = childRuntime("1.0.0", true, List.of("mate-model-manager/model-a"));
        useRuntime(child, completedSession("child answer"));

        execute(parent, rootContext(), new CancellationToken());

        ArgumentCaptor<ManagedAgentSessionRequest> request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory).create(request.capture());
        Model selected = request.getValue().modelResolver().apply(child);
        assertThat(selected.id()).isEqualTo("mate-model-manager/model-a");
        assertThat(selected.id()).isNotEqualTo("model-a");
        assertThat(selected.provider()).isEqualTo(Provider.CAMPUSMATE);
    }

    @Test
    void doesNotTreatLegacyQualifiedBindingAsCurrentCampusMateIdentity() {
        RuntimeModelManager manager = mock(RuntimeModelManager.class);
        when(manager.resolveDefaultModel(any())).thenReturn(model);
        service = new SubagentExecutionService(sessionFactory, manager);
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        PreparedAgentRuntime child = childRuntime("1.0.0", true, List.of("mate-model-manager/model-a"));
        useRuntime(child, completedSession("unused"));

        assertThatThrownBy(() -> execute(parent, rootContext(), new CancellationToken()))
                .hasMessage("Child Agent does not allow the selected model");
    }

    @Test
    void propagatesExplicitChildDefaultResolutionFailureWithoutInheritingParentModel() {
        RuntimeModelManager manager = mock(RuntimeModelManager.class);
        when(manager.resolveDefaultModel(any()))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
        service = new SubagentExecutionService(sessionFactory, manager);
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        useRuntime(childRuntime("1.0.0", true), completedSession("unused"));

        assertThatThrownBy(() -> execute(parent, rootContext(), new CancellationToken()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
    }

    @Test
    void rejectsDepthCycleAndDisabledChild() {
        PreparedAgentRuntime parent = parentRuntime("1.0.0");
        var atLimit =
                new SubagentExecutionContext(1, Set.of(PARENT_ID), model, ThinkingLevel.OFF, MateCredentials.empty());
        assertThatThrownBy(() -> execute(parent, atLimit, new CancellationToken()))
                .hasMessage("Child Agent depth limit exceeded");

        var cycle = new SubagentExecutionContext(
                0, Set.of(PARENT_ID, CHILD_ID), model, ThinkingLevel.OFF, MateCredentials.empty());
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
        verify(session, never()).prompt(any(String.class));
    }

    private com.huawei.hicampus.claw.agent.tool.AgentToolResult execute(
            PreparedAgentRuntime parent, SubagentExecutionContext context, CancellationToken token) {
        return service.execute(parent, context, "researcher", "task", token, ignored -> {});
    }

    private SubagentExecutionContext rootContext() {
        return SubagentExecutionContext.root(PARENT_ID, model, ThinkingLevel.OFF, MateCredentials.empty());
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
        return new PreparedAgentRuntime(
                PARENT_ID,
                Path.of("agent", PARENT_ID),
                runtime,
                List.of(),
                com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptTestFixtures.prompts("parent"));
    }

    private PreparedAgentRuntime childRuntime(String version, boolean enabled) {
        return childRuntime(version, enabled, List.of(model.id()));
    }

    private PreparedAgentRuntime childRuntime(String version, boolean enabled, List<String> bindingModels) {
        AgentRuntime runtime = runtime(CHILD_ID, "researcher", version, enabled, List.of(), bindingModels);
        return new PreparedAgentRuntime(
                CHILD_ID,
                Path.of("agent", CHILD_ID),
                runtime,
                List.of(),
                com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptTestFixtures.prompts("child"));
    }

    private AgentRuntime runtime(
            String id, String name, String version, boolean enabled, List<AgentReference> children) {
        return runtime(id, name, version, enabled, children, List.of(model.id()));
    }

    private AgentRuntime runtime(
            String id,
            String name,
            String version,
            boolean enabled,
            List<AgentReference> children,
            List<String> bindingModels) {
        return new AgentRuntime(
                bindingModels,
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
