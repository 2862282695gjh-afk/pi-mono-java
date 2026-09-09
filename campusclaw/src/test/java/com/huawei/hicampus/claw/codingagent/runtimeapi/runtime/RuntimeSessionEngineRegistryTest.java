/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.cron.AgentScopedCronToolFactory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuntimeSessionEngineRegistryTest {

    @Test
    void listsBoundExecutionTargetsInStableOrder() {
        AgentSessionFactory sessionFactory = mock(AgentSessionFactory.class);
        ManagedAgentSession session = session(mock(Agent.class));
        when(sessionFactory.create(any())).thenReturn(session);
        RuntimeSessionEngineRegistry registry = registry(sessionFactory, 3);
        RuntimeActiveExecution second = execution("session-b", "execution-b", "segment-b");
        RuntimeActiveExecution first = execution("session-a", "execution-a", "segment-a");
        RuntimeActiveExecution unbound = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());

        registry.register("session-b", snapshot("agent-b"), mock(Model.class), false, List.of(), second, credentials());
        registry.register("session-a", snapshot("agent-a"), mock(Model.class), false, List.of(), first, credentials());
        registry.register(
                "session-c", snapshot("agent-c"), mock(Model.class), false, List.of(), unbound, credentials());

        assertThat(registry.activeTargets(3)).containsExactly(first.target(), second.target());
        assertThat(registry.activeTargets(1)).containsExactly(first.target());
        assertThat(registry.activeTargets(0)).isEmpty();
    }

    @Test
    void releasesOperationLockWhenOperationFails() throws Exception {
        RuntimeSessionEngineRegistry registry = registry(1);

        assertThatThrownBy(() -> registry.withOperationLock("session-a", () -> {
                    throw new IllegalStateException("expected test failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected test failure");

        ExecutorService executor =
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        try {
            Future<String> nextOperation =
                    executor.submit(() -> registry.withOperationLock("session-a", () -> "completed"));

            assertThat(nextOperation.get(1L, TimeUnit.SECONDS)).isEqualTo("completed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateRegistrationClosesTheRejectedSession() {
        AgentSessionFactory sessionFactory = mock(AgentSessionFactory.class);
        ManagedAgentSession firstSession = session(mock(Agent.class));
        ManagedAgentSession rejectedSession = session(mock(Agent.class));
        when(sessionFactory.create(any())).thenReturn(firstSession, rejectedSession);
        var registry = registry(sessionFactory, 2);
        var snapshot = new AgentDirectorySnapshotDTO(
                "agent-a", "model-a", List.of("model-a"), Path.of("/agent-a"), Path.of("/agent-a/.campusclaw"));
        Model model = mock(Model.class);
        RuntimeActiveExecution firstExecution = mock(RuntimeActiveExecution.class);
        RuntimeActiveExecution rejectedExecution = mock(RuntimeActiveExecution.class);
        MateCredentials credentials = MateCredentials.appKey("caller-1", "app-key-1", "access-token-1");

        RuntimeSessionHolder accepted =
                registry.register("session-a", snapshot, model, false, List.of(), firstExecution, credentials);

        assertThatThrownBy(() -> registry.register(
                        "session-a", snapshot, model, false, List.of(), rejectedExecution, credentials))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> org.assertj.core.api.Assertions.assertThat(
                                error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_BUSY));
        ArgumentCaptor<ManagedAgentSessionRequest> requests = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(sessionFactory, times(2)).create(requests.capture());
        org.assertj.core.api.Assertions.assertThat(requests.getAllValues())
                .extracting(ManagedAgentSessionRequest::mateCredentials)
                .containsOnly(credentials);
        verify(rejectedSession).close();

        registry.complete(accepted, firstExecution);
        verify(firstSession).close();
    }

    private static ManagedAgentSession session(Agent agent) {
        ManagedAgentSession session = mock(ManagedAgentSession.class);
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        when(metadata.bindingTools()).thenReturn(List.of());
        when(runtime.metadata()).thenReturn(metadata);
        when(runtime.skills()).thenReturn(List.of());
        when(session.agent()).thenReturn(agent);
        when(session.runtime()).thenReturn(runtime);
        return session;
    }

    private static RuntimeSessionEngineRegistry registry(int maxActive) {
        return registry(mock(AgentSessionFactory.class), maxActive);
    }

    private static RuntimeSessionEngineRegistry registry(AgentSessionFactory sessionFactory, int maxActive) {
        var properties = new RuntimeExecutionProperties();
        properties.setMaxActive(maxActive);
        return new RuntimeSessionEngineRegistry(
                sessionFactory,
                mock(SubagentExecutionService.class),
                mock(AgentScopedCronToolFactory.class),
                properties);
    }

    private static RuntimeActiveExecution execution(String sessionId, String executionId, String segmentId) {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.bindTarget(new ExecutionTargetDTO(sessionId, executionId, "root-" + sessionId, segmentId));
        return execution;
    }

    private static AgentDirectorySnapshotDTO snapshot(String agentId) {
        return new AgentDirectorySnapshotDTO(
                agentId,
                "model-a",
                List.of("model-a"),
                Path.of("/" + agentId),
                Path.of("/" + agentId + "/.campusclaw"));
    }

    private static MateCredentials credentials() {
        return MateCredentials.appKey("caller-1", "app-key-1", "access-token-1");
    }
}
