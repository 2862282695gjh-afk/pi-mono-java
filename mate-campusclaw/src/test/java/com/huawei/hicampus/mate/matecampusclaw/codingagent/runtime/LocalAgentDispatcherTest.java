/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentMetadata;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalAgentDispatcherTest {

    private AgentRuntimeManager runtimeManager;
    private TransientAgentRunner runner;
    private AgentBindingResolver resolver;
    private LocalAgentDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        runtimeManager = mock(AgentRuntimeManager.class);
        runner = mock(TransientAgentRunner.class);
        AgentBindingResolver.ChildAgentMetadataSource metadataSource =
                agentId -> Optional.of(new ChildAgentMetadata(agentId, "9.9", true));
        resolver = new AgentBindingResolver(metadataSource, AgentAuthorizationPolicy.PERMIT_ALL);
        dispatcher = new LocalAgentDispatcher(resolver, runtimeManager, runner);
    }

    @Test
    void dispatchRunsChildWithContextFromEntry() {
        DelegationState entry = entryState();
        PreparedAgentRuntime parent = runtime("agent-1", List.of(binding("agent-2")));
        PreparedAgentRuntime child = runtime("agent-2", List.of());
        when(runtimeManager.prepare("agent-2")).thenReturn(child);
        when(runner.run(eq(child), any(), eq("do it"), eq("glm-5"))).thenReturn("child answer");

        String answer = dispatcher.dispatch(entry, parent, "agent-2", "do it", "glm-5");

        assertEquals("child answer", answer);
        ArgumentCaptor<DelegationState> stateCaptor = ArgumentCaptor.forClass(DelegationState.class);
        verify(runner).run(eq(child), stateCaptor.capture(), eq("do it"), eq("glm-5"));
        DelegationState childState = stateCaptor.getValue();
        assertEquals(1, childState.depth());
        assertEquals(List.of("agent-1", "agent-2"), childState.invocationChain("agent-2"));
        assertEquals(entry.conversationId(), childState.conversationId());
    }

    @Test
    void dispatchRejectsTargetOutsideDirectBindings() {
        DelegationState entry = entryState();
        PreparedAgentRuntime parent = runtime("agent-1", List.of(binding("agent-2")));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> dispatcher.dispatch(entry, parent, "agent-9", "do it", "glm-5"));

        assertTrue(error.getMessage().contains("NOT_DIRECTLY_BOUND"));
        verify(runtimeManager, org.mockito.Mockito.never()).prepare(anyString());
    }

    @Test
    void dispatchRejectsThirdHopAtDepthCap() {
        DelegationContext secondHop = DelegationContext.forEntry("agent-1", "agent-2", "conv", "inv-1")
                .delegateTo("agent-3", "inv-2");
        DelegationState secondLevel =
                new DelegationState(dispatcher, "conv", new AgentPrincipal(null, null), secondHop, wiring());
        PreparedAgentRuntime thirdAgent = runtime("agent-3", List.of(binding("agent-4")));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class,
                () -> dispatcher.dispatch(secondLevel, thirdAgent, "agent-4", "do it", "glm-5"));

        assertTrue(error.getMessage().contains("DEPTH_EXCEEDED"));
    }

    private DelegationState entryState() {
        return DelegationState.entry(dispatcher, "conv-1", new AgentPrincipal(null, null), wiring());
    }

    private static DelegationWiring wiring() {
        return new DelegationWiring(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolSelection.all(),
                null);
    }

    private static AgentReference binding(String agentId) {
        return new AgentReference(agentId, "child", "Child", "Child agent", null);
    }

    private static PreparedAgentRuntime runtime(String agentId, List<AgentReference> bindings) {
        AgentRuntime metadata = new AgentRuntime(
                List.of("glm-5"),
                List.<SkillReference>of(),
                List.<BoundTool>of(),
                bindings,
                List.of("d"),
                "n",
                Boolean.TRUE,
                agentId,
                agentId,
                "prompt",
                List.of("campus"),
                "1");
        return new PreparedAgentRuntime(
                agentId, Path.of("/tmp/agents").resolve(agentId), metadata, List.<SkillInfo>of());
    }
}
