/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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

import com.campusclaw.agent.Agent;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.campusclaw.codingagent.runtimeapi.auth.CredentialMode;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import jakarta.validation.Validation;

/**
 * Session 模型与深度思考配置 Service 的状态机和并发编排测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionConfigurationServiceTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private static final String AGENT_ID = "agent_011CZkYqphY8vELVzwCUpqiQ";

    private static final String OWNER_ID = "mate-service";

    private RuntimeSessionRepository repository;

    private AgentRuntimeSnapshotProvider snapshotProvider;

    private RuntimeModelManager modelManager;

    private RuntimeSessionEngineRegistry engineRegistry;

    private SessionEtagFactory etagFactory;

    private RuntimeSessionConfigurationService service;

    private AgentRuntimeSnapshotDTO snapshot;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        snapshotProvider = mock(AgentRuntimeSnapshotProvider.class);
        modelManager = mock(RuntimeModelManager.class);
        engineRegistry = mock(RuntimeSessionEngineRegistry.class);
        etagFactory = new SessionEtagFactory();
        snapshot = new AgentRuntimeSnapshotDTO(
                AGENT_ID, "revision-1", "model-a", List.of("model-a", "model-b"), Path.of("/runtime/revision-1"));
        when(engineRegistry.find(any())).thenReturn(Optional.empty());
        when(snapshotProvider.resolveRevision(AGENT_ID, "revision-1")).thenReturn(snapshot);
        service = new RuntimeSessionConfigurationService(
                repository,
                snapshotProvider,
                modelManager,
                engineRegistry,
                etagFactory,
                Validation.buildDefaultValidatorFactory().getValidator(),
                Clock.fixed(Instant.parse("2026-08-18T02:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listsCurrentModelAndCallerScopedStringArray() {
        RuntimeSessionDTO session = session("model-a", "idle", false, 1);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session));
        when(modelManager.listAvailableModels(snapshot, caller())).thenReturn(List.of("model-a", "model-b"));

        var result = service.listModels(SESSION_ID, caller());

        assertThat(result.getCurrentModelId()).isEqualTo("model-a");
        assertThat(result.getModels()).containsExactly("model-a", "model-b");
    }

    @Test
    void modelChangeRequiresIfMatchBeforeReadingSession() {
        assertThatThrownBy(() -> service.changeModel(SESSION_ID, caller(), null, modelRequest("model-b")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.IF_MATCH_REQUIRED);
                });

        verify(repository, never()).find(any());
    }

    @Test
    void staleIfMatchFailsBeforeCallingModelManager() {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("model-a", "idle", false, 2)));

        assertThatThrownBy(() -> service.changeModel(
                        SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), modelRequest("model-b")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_VERSION_MISMATCH));

        verify(modelManager, never()).resolveAvailableModel(any(), any(), any());
    }

    @Test
    void modelChangeReturnsUpdatedResourceAndNormalizesThinking() {
        RuntimeSessionDTO current = session("model-a", "idle", true, 1);
        RuntimeSessionDTO updated = session("model-b", "idle", false, 2);
        Model target = model("model-b", false);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveAvailableModel(snapshot, caller(), "model-b")).thenReturn(target);
        when(repository.updateModel(eq(SESSION_ID), eq(OWNER_ID), eq(1L), eq("model-b"), eq(false), any()))
                .thenReturn(update(SessionConfigurationUpdate.Status.UPDATED, updated));

        var result =
                service.changeModel(SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), modelRequest("model-b"));

        assertThat(result.resource().getModelId()).isEqualTo("model-b");
        assertThat(result.resource().isThinking()).isFalse();
        assertThat(result.etag()).isEqualTo(etagFactory.create(SESSION_ID, 2));
    }

    @Test
    void unavailableModelDoesNotReachPersistence() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveAvailableModel(snapshot, caller(), "model-b"))
                .thenThrow(
                        new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.MODEL_NOT_AVAILABLE));

        assertThatThrownBy(() -> service.changeModel(
                        SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), modelRequest("model-b")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE));

        verify(repository, never()).updateModel(any(), any(), anyLong(), any(), anyBoolean(), any());
    }

    @Test
    void thinkingTrueRejectsModelWithoutReasoning() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1);
        Model currentModel = model("model-a", false);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveModel(snapshot, "model-a")).thenReturn(currentModel);

        assertThatThrownBy(() -> service.changeThinking(
                        SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), thinkingRequest(true)))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.THINKING_NOT_SUPPORTED));

        verify(repository, never()).updateThinking(any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    void thinkingTrueDoesNotExposeMissingCurrentModelDetails() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveModel(snapshot, "model-a"))
                .thenThrow(new RuntimeApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));

        assertThatThrownBy(() -> service.changeThinking(
                        SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), thinkingRequest(true)))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.THINKING_NOT_SUPPORTED);
                });
    }

    @Test
    void thinkingCapabilityInfrastructureFailureUsesOperationError() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveModel(snapshot, "model-a"))
                .thenThrow(
                        new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, RuntimeErrorCode.MANAGER_UNAVAILABLE));

        assertThatThrownBy(() -> service.changeThinking(
                        SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), thinkingRequest(true)))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_THINKING_UPDATE_FAILED);
                });
    }

    @Test
    void thinkingFalseIsAllowedWithoutCapabilityLookup() {
        RuntimeSessionDTO current = session("model-a", "idle", true, 1);
        RuntimeSessionDTO updated = session("model-a", "idle", false, 2);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(repository.updateThinking(eq(SESSION_ID), eq(OWNER_ID), eq(1L), eq(false), any()))
                .thenReturn(update(SessionConfigurationUpdate.Status.UPDATED, updated));

        var result =
                service.changeThinking(SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), thinkingRequest(false));

        assertThat(result.resource().isThinking()).isFalse();
        verify(modelManager, never()).resolveModel(any(), any());
    }

    @Test
    void databaseRaceMapsToOperationSpecificBusyMessage() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1);
        RuntimeSessionDTO running = session("model-a", "running", false, 1);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(repository.updateThinking(eq(SESSION_ID), eq(OWNER_ID), eq(1L), eq(false), any()))
                .thenReturn(update(SessionConfigurationUpdate.Status.BUSY, running));

        assertThatThrownBy(() -> service.changeThinking(
                        SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), thinkingRequest(false)))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_BUSY);
                    assertThat(error.localizedMessage(false))
                            .isEqualTo("Deep thinking cannot be changed while the Session is running.");
                });
    }

    @Test
    void modelAndAgentUpdateAreProtectedBySameOperationLock() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1);
        RuntimeSessionDTO updated = session("model-b", "idle", true, 2);
        RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        Agent agent = mock(Agent.class);
        Model target = model("model-b", true);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(engineRegistry.find(SESSION_ID)).thenReturn(Optional.of(holder));
        when(holder.snapshot()).thenReturn(snapshot);
        when(holder.agent()).thenReturn(agent);
        when(modelManager.resolveAvailableModel(snapshot, caller(), "model-b")).thenReturn(target);
        when(repository.updateModel(eq(SESSION_ID), eq(OWNER_ID), eq(1L), eq("model-b"), eq(true), any()))
                .thenReturn(update(SessionConfigurationUpdate.Status.UPDATED, updated));

        service.changeModel(SESSION_ID, caller(), etagFactory.create(SESSION_ID, 1), modelRequest("model-b"));

        var ordered = inOrder(engineRegistry, repository, agent);
        ordered.verify(engineRegistry).lockOperation(SESSION_ID);
        ordered.verify(repository).updateModel(eq(SESSION_ID), eq(OWNER_ID), eq(1L), eq("model-b"), eq(true), any());
        ordered.verify(agent).setModel(target);
        ordered.verify(agent).setThinkingLevel(ThinkingLevel.MEDIUM);
        ordered.verify(engineRegistry).unlockOperation(SESSION_ID);
    }

    private static CallerAuthContext caller() {
        return new CallerAuthContext(OWNER_ID, CredentialMode.JWT);
    }

    private static ChangeModelRequestVO modelRequest(String modelId) {
        ChangeModelRequestVO request = new ChangeModelRequestVO();
        request.readModelId(TextNode.valueOf(modelId));
        return request;
    }

    private static ChangeThinkingRequestVO thinkingRequest(boolean thinking) {
        ChangeThinkingRequestVO request = new ChangeThinkingRequestVO();
        request.readThinking(BooleanNode.valueOf(thinking));
        return request;
    }

    private static Model model(String modelId, boolean reasoning) {
        Model model = mock(Model.class);
        when(model.id()).thenReturn(modelId);
        when(model.reasoning()).thenReturn(reasoning);
        return model;
    }

    private static SessionConfigurationUpdate update(
            SessionConfigurationUpdate.Status status, RuntimeSessionDTO session) {
        return new SessionConfigurationUpdate(status, session);
    }

    private static RuntimeSessionDTO session(String modelId, String state, boolean thinking, long version) {
        OffsetDateTime created = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(SESSION_ID);
        session.setAgentId(AGENT_ID);
        session.setOwnerId(OWNER_ID);
        session.setBundleRevision("revision-1");
        session.setModelId(modelId);
        session.setState(state);
        session.setThinking(thinking);
        session.setResourceVersion(version);
        session.setCreatedAt(created);
        session.setUpdatedAt(version == 1 ? created : OffsetDateTime.parse("2026-08-18T02:00:00Z"));
        return session;
    }
}
