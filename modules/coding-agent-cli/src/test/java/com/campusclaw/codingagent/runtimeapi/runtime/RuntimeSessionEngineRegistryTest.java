/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

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

import com.campusclaw.agent.Agent;
import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.ManagedAgentSession;
import com.campusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.cron.AgentScopedCronToolFactory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuntimeSessionEngineRegistryTest {

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
        when(session.agent()).thenReturn(agent);
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
}
