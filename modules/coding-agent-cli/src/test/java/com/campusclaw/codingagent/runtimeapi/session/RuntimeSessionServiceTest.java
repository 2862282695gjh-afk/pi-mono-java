/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.campusclaw.codingagent.runtimeapi.auth.CredentialMode;
import com.campusclaw.codingagent.runtimeapi.auth.RuntimeAgentAuthorizer;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runtime Session 创建、授权和删除事务编排的单元测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionServiceTest {
    private static final String AGENT_ID = "agent_011CZkYqphY8vELVzwCUpqiQ";

    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeSessionRepository repository;

    private RuntimeAgentAuthorizer agentAuthorizer;

    private AgentRuntimeSnapshotProvider snapshotProvider;

    private RuntimeModelManager modelManager;

    private RuntimeSessionEngineRegistry engineRegistry;

    private RuntimeSessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        agentAuthorizer = mock(RuntimeAgentAuthorizer.class);
        snapshotProvider = mock(AgentRuntimeSnapshotProvider.class);
        modelManager = mock(RuntimeModelManager.class);
        engineRegistry = mock(RuntimeSessionEngineRegistry.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);
        service = new RuntimeSessionService(
                repository,
                agentAuthorizer,
                snapshotProvider,
                modelManager,
                engineRegistry,
                () -> SESSION_ID,
                new SessionEtagFactory(),
                clock);
        when(agentAuthorizer.canCreateSession(any(), any())).thenReturn(true);
    }

    @Test
    void createRejectsCallerWithoutAgentPermission() {
        when(agentAuthorizer.canCreateSession(AGENT_ID, caller("mate-service"))).thenReturn(false);

        assertThatThrownBy(() -> service.create(AGENT_ID, caller("mate-service")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(error.localizedMessage(true)).isEqualTo("当前调用方无权为该 Agent 创建 Session。");
                });
        verify(snapshotProvider, never()).resolveCurrent(any());
    }

    @Test
    void createPinsSnapshotAndPersistsIdleSession() {
        AgentRuntimeSnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(model.id()).thenReturn("model-default");
        when(snapshotProvider.resolveCurrent(AGENT_ID)).thenReturn(snapshot);
        when(modelManager.resolveDefaultModel(snapshot)).thenReturn(model);

        var view = service.create(AGENT_ID, caller("mate-service"));

        assertThat(view.resource().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(view.resource().getModelId()).isEqualTo("model-default");
        assertThat(view.resource().isThinking()).isFalse();
        assertThat(view.etag()).startsWith("\"snp-");
        verify(engineRegistry).initialize(SESSION_ID, snapshot, model, false);
        verify(repository).create(any(RuntimeSessionDTO.class));
    }

    @Test
    void createFailureRemovesPreparedRuntimeObject() {
        AgentRuntimeSnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(model.id()).thenReturn("model-default");
        when(snapshotProvider.resolveCurrent(AGENT_ID)).thenReturn(snapshot);
        when(modelManager.resolveDefaultModel(snapshot)).thenReturn(model);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .create(any());

        assertThatThrownBy(() -> service.create(AGENT_ID, caller("mate-service")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_INITIALIZATION_FAILED));
        verify(engineRegistry).abortAndRemove(SESSION_ID);
    }

    @Test
    void getRejectsDifferentOwner() {
        RuntimeSessionDTO session = session("owner-a");
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.get(SESSION_ID, caller("owner-b")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.FORBIDDEN);
                    assertThat(error.localizedMessage(true)).isEqualTo("当前调用方无权访问该 Session。");
                });
    }

    @Test
    void deleteIsIdempotentWhenSessionDoesNotExist() {
        when(repository.find(SESSION_ID)).thenReturn(Optional.empty());

        service.delete(SESSION_ID, caller("mate-service"));

        verify(repository, never()).beginDeletion(any(), any());
        verify(engineRegistry, never()).abortAndRemove(any());
    }

    @Test
    void deleteCommitsTombstoneWorkflowBeforeRemovingRuntimeObject() {
        RuntimeSessionDTO session = session("mate-service");
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
        when(repository.beginDeletion(org.mockito.ArgumentMatchers.eq(SESSION_ID), any()))
                .thenReturn(true);

        service.delete(SESSION_ID, caller("mate-service"));

        verify(repository).beginDeletion(org.mockito.ArgumentMatchers.eq(SESSION_ID), any());
        verify(engineRegistry).abortAndRemove(SESSION_ID);
    }

    private static CallerAuthContext caller(String callerId) {
        return new CallerAuthContext(callerId, CredentialMode.JWT);
    }

    private static AgentRuntimeSnapshotDTO snapshot() {
        return new AgentRuntimeSnapshotDTO(
                AGENT_ID, "revision-1", "model-default", List.of("model-default"), Path.of("/runtime/revision-1"));
    }

    private static RuntimeSessionDTO session(String ownerId) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(SESSION_ID);
        session.setOwnerId(ownerId);
        session.setAgentId(AGENT_ID);
        session.setModelId("model-default");
        session.setState("idle");
        session.setResourceVersion(1);
        session.setCreatedAt(java.time.OffsetDateTime.parse("2026-08-18T00:00:00Z"));
        session.setUpdatedAt(session.getCreatedAt());
        return session;
    }
}
