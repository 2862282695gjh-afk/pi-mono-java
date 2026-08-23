/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.SessionDeletionStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runtime Session 创建、读取和删除编排测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionServiceTest {
    private static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private RuntimeSessionRepository repository;

    private AgentDirectoryResolver directoryResolver;

    private RuntimeModelManager modelManager;

    private RuntimeAgentPromptLoader promptLoader;

    private RuntimeSessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        directoryResolver = mock(AgentDirectoryResolver.class);
        modelManager = mock(RuntimeModelManager.class);
        promptLoader = mock(RuntimeAgentPromptLoader.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);
        service = new RuntimeSessionService(
                repository,
                directoryResolver,
                modelManager,
                promptLoader,
                () -> SESSION_ID,
                new RuntimeSessionResponseAssembler(new SessionEtagFactory()),
                clock);
    }

    @Test
    void createValidatesAgentAndPersistsOnlyIdleSessionMetadata() {
        AgentDirectorySnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(directoryResolver.resolve(AGENT_ID)).thenReturn(snapshot);
        when(modelManager.resolveDefaultModel(snapshot)).thenReturn(model);
        when(model.id()).thenReturn("model-default");
        when(model.reasoning()).thenReturn(true);

        var view = service.create(AGENT_ID);

        assertThat(view.resource().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(view.resource().getState()).isEqualTo("idle");
        assertThat(view.resource().isThinking()).isTrue();
        verify(promptLoader).validate(snapshot.runtimeDirectory());
        verify(repository)
                .create(org.mockito.ArgumentMatchers.argThat(session -> SESSION_ID.equals(session.getId())
                        && AGENT_ID.equals(session.getAgentId())
                        && snapshot.agentRoot().toString().equals(session.getCwd())));
    }

    @Test
    void createMapsPersistenceFailureWithoutExposingInternalCause() {
        AgentDirectorySnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(directoryResolver.resolve(AGENT_ID)).thenReturn(snapshot);
        when(modelManager.resolveDefaultModel(snapshot)).thenReturn(model);
        when(model.id()).thenReturn("model-default");
        when(model.reasoning()).thenReturn(true);
        doThrow(new IllegalStateException("database password leaked"))
                .when(repository)
                .create(any(RuntimeSessionDTO.class));

        assertThatThrownBy(() -> service.create(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_INITIALIZATION_FAILED));
    }

    @Test
    void createRejectsDefaultModelWithoutThinkingCapability() {
        AgentDirectorySnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(directoryResolver.resolve(AGENT_ID)).thenReturn(snapshot);
        when(modelManager.resolveDefaultModel(snapshot)).thenReturn(model);

        assertThatThrownBy(() -> service.create(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
        verify(repository, never()).create(any(RuntimeSessionDTO.class));
    }

    @Test
    void getReturnsPersistedResourceWithoutResolvingAgentOrModel() {
        RuntimeSessionDTO session = session();
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));

        var view = service.get(SESSION_ID);

        assertThat(view.resource().getAgentId()).isEqualTo(AGENT_ID);
        assertThat(view.resource().getUpdatedAt()).isEqualTo(session.getUpdatedAt());
        assertThat(view.etag()).startsWith("\"snp-");
    }

    @Test
    void deleteIsIdempotentForUnknownSession() {
        when(repository.beginDeletion(eq(SESSION_ID), any())).thenReturn(SessionDeletionStatus.NOT_FOUND);

        service.delete(SESSION_ID);

        verify(repository).beginDeletion(eq(SESSION_ID), any());
    }

    @Test
    void deleteRejectsRunningSession() {
        when(repository.beginDeletion(eq(SESSION_ID), any())).thenReturn(SessionDeletionStatus.BUSY);

        assertThatThrownBy(() -> service.delete(SESSION_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_BUSY);
                    assertThat(error.status().value()).isEqualTo(409);
                });
    }

    private static AgentDirectorySnapshotDTO snapshot() {
        return new AgentDirectorySnapshotDTO(
                AGENT_ID,
                "model-default",
                List.of("model-default"),
                Path.of("/runtime/agents").resolve(AGENT_ID),
                Path.of("/runtime/agents").resolve(AGENT_ID).resolve(".campusclaw"));
    }

    private static RuntimeSessionDTO session() {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(SESSION_ID);
        session.setAgentId(AGENT_ID);
        session.setModelId("model-default");
        session.setState("idle");
        session.setResourceVersion(1L);
        session.setCreatedAt(time);
        session.setUpdatedAt(time);
        return session;
    }
}
