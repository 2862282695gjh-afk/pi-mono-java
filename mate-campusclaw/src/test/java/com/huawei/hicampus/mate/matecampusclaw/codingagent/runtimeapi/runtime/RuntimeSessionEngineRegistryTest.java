/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.cron.AgentScopedCronToolFactory;

import org.junit.jupiter.api.Test;

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

        RuntimeSessionHolder accepted =
                registry.register("session-a", snapshot, model, false, List.of(), firstExecution);

        assertThatThrownBy(() -> registry.register("session-a", snapshot, model, false, List.of(), rejectedExecution))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> org.assertj.core.api.Assertions.assertThat(
                                error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_BUSY));
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
