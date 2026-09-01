/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.cron.AgentScopedCronToolFactory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuntimeSessionEngineRegistryTest {

    @Test
    void duplicateRegistrationClosesTheRejectedSession() {
        AgentSessionFactory sessionFactory = mock(AgentSessionFactory.class);
        ManagedAgentSession firstSession = session(mock(Agent.class));
        ManagedAgentSession rejectedSession = session(mock(Agent.class));
        when(sessionFactory.create(any())).thenReturn(firstSession, rejectedSession);
        var properties = new RuntimeExecutionProperties();
        properties.setMaxActive(2);
        var registry = new RuntimeSessionEngineRegistry(
                sessionFactory,
                mock(SubagentExecutionService.class),
                mock(AgentScopedCronToolFactory.class),
                properties);
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
}
