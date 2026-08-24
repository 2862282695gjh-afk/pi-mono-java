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
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Session 模型与深度思考配置状态机测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionConfigurationServiceTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

    private RuntimeSessionRepository repository;

    private AgentDirectoryResolver directoryResolver;

    private RuntimeModelManager modelManager;

    private SessionEtagFactory etagFactory;

    private RuntimeSessionConfigurationService service;

    private AgentDirectorySnapshotDTO snapshot;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        directoryResolver = mock(AgentDirectoryResolver.class);
        modelManager = mock(RuntimeModelManager.class);
        etagFactory = new SessionEtagFactory();
        snapshot = new AgentDirectorySnapshotDTO(
                AGENT_ID,
                "model-a",
                List.of("model-a", "model-b"),
                Path.of("/runtime/agent"),
                Path.of("/runtime/agent/.campusclaw"));
        when(directoryResolver.resolve(AGENT_ID)).thenReturn(snapshot);
        service = new RuntimeSessionConfigurationService(
                repository,
                directoryResolver,
                modelManager,
                etagFactory,
                new RuntimeSessionResponseAssembler(etagFactory),
                new RuntimeEntryCodec(new ObjectMapper()),
                () -> "entry-config",
                Clock.fixed(Instant.parse("2026-08-18T02:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listsCurrentModelAndOrderedStringArray() {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("model-a", "idle", false, 1L)));
        when(modelManager.listAvailableModels(snapshot)).thenReturn(List.of("model-b", "model-a"));

        var response = service.listModels(SESSION_ID);

        assertThat(response.getCurrentModelId()).isEqualTo("model-a");
        assertThat(response.getModels()).containsExactly("model-b", "model-a");
    }

    @Test
    void modelChangeRequiresCurrentStrongEtagBeforeModelLookup() {
        assertThatThrownBy(() -> service.changeModel(SESSION_ID, null, modelRequest("model-b")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.IF_MATCH_REQUIRED));
        verify(repository, never()).find(any());
    }

    @Test
    void staleEtagDoesNotCallModelManager() {
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(session("model-a", "idle", false, 2L)));

        assertThatThrownBy(() ->
                        service.changeModel(SESSION_ID, etagFactory.create(SESSION_ID, 1L), modelRequest("model-b")))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_VERSION_MISMATCH));
        verify(modelManager, never()).resolveAvailableModel(any(), any());
    }

    @Test
    void modelChangeAtomicallyNormalizesUnsupportedThinking() {
        RuntimeSessionDTO current = session("model-a", "idle", true, 1L);
        RuntimeSessionDTO updated = session("model-b", "idle", false, 2L);
        Model model = model("model-b", false);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveAvailableModel(snapshot, "model-b")).thenReturn(model);
        when(repository.updateModel(eq(SESSION_ID), eq(1L), eq("model-b"), eq(false), any(), any()))
                .thenReturn(update(SessionConfigurationUpdate.Status.UPDATED, updated));

        var view = service.changeModel(SESSION_ID, etagFactory.create(SESSION_ID, 1L), modelRequest("model-b"));

        assertThat(view.resource().getModelId()).isEqualTo("model-b");
        assertThat(view.resource().isThinking()).isFalse();
        assertThat(view.etag()).isEqualTo(etagFactory.create(SESSION_ID, 2L));
    }

    @Test
    void enablingThinkingRejectsModelWithoutReasoning() {
        RuntimeSessionDTO current = session("model-a", "idle", false, 1L);
        Model model = model("model-a", false);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(modelManager.resolveModel(snapshot, "model-a")).thenReturn(model);

        assertThatThrownBy(() ->
                        service.changeThinking(SESSION_ID, etagFactory.create(SESSION_ID, 1L), thinkingRequest(true)))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.THINKING_NOT_SUPPORTED));
        verify(repository, never()).updateThinking(any(), anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void disablingThinkingDoesNotResolveModelCapability() {
        RuntimeSessionDTO current = session("model-a", "idle", true, 1L);
        RuntimeSessionDTO updated = session("model-a", "idle", false, 2L);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(current));
        when(repository.updateThinking(eq(SESSION_ID), eq(1L), eq(false), any(), any()))
                .thenReturn(update(SessionConfigurationUpdate.Status.UPDATED, updated));

        var view = service.changeThinking(SESSION_ID, etagFactory.create(SESSION_ID, 1L), thinkingRequest(false));

        assertThat(view.resource().isThinking()).isFalse();
        verify(modelManager, never()).resolveModel(any(), any());
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
        session.setModelId(modelId);
        session.setState(state);
        session.setThinking(thinking);
        session.setResourceVersion(version);
        session.setCreatedAt(created);
        session.setUpdatedAt(version == 1L ? created : OffsetDateTime.parse("2026-08-18T02:00:00Z"));
        return session;
    }
}
