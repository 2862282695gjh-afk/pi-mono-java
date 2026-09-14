/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.GetSessionResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.UpdateSessionRequestVO;
import com.huawei.hicampus.claw.codingagent.session.PlanPolicyPromptComposer;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolProperties;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证统一 Session 更新只准备已提交字段依赖，并保持同值准入语义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionUpdateServiceTest {
    private RuntimeSessionRepository repository;

    private AgentDirectoryResolver directories;

    private RuntimeModelManager models;

    private RuntimeSessionUpdateService service;

    private RuntimeSessionDTO session;

    private SessionEtagFactory etags;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        directories = mock(AgentDirectoryResolver.class);
        models = mock(RuntimeModelManager.class);
        etags = new SessionEtagFactory();
        session = session();
        when(repository.find(session.getId())).thenReturn(java.util.Optional.of(session));
        when(repository.updateSession(eq(session.getId()), any(), any(), any()))
                .thenAnswer(invocation ->
                        new SessionConfigurationUpdateDTO(SessionConfigurationUpdateDTO.Status.UPDATED, session));
        RuntimeSessionResponseAssembler assembler = mock(RuntimeSessionResponseAssembler.class);
        when(assembler.getView(any())).thenReturn(new RuntimeSessionView<>(mock(GetSessionResponseVO.class), "etag"));
        service = new RuntimeSessionUpdateService(
                repository,
                directories,
                models,
                new BuiltInToolProperties(),
                new PlanPolicyPromptComposer(),
                mock(RuntimeEntryCodec.class),
                mock(RuntimeCommittedEventFactory.class),
                mock(RuntimeEntryIdGenerator.class),
                assembler,
                etags,
                Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void nameOnlyUpdateCanBePreparedWhileRunningWithoutModelDependencies() {
        session.setState("running");
        UpdateSessionRequestVO request = new UpdateSessionRequestVO();
        request.readDisplayName(TextNode.valueOf(" 新名称 "));

        service.update(session.getId(), etag(), request);

        verify(repository).updateSession(eq(session.getId()), any(), any(), any());
        verifyNoInteractions(directories, models);
    }

    @Test
    void disablingThinkingDoesNotResolveTheCurrentModel() {
        session.setThinking(true);
        UpdateSessionRequestVO request = new UpdateSessionRequestVO();
        request.readThinking(BooleanNode.FALSE);

        service.update(session.getId(), etag(), request);

        verifyNoInteractions(directories, models);
    }

    @Test
    void samePlanModeStillChecksRuntimeProfileAndAgentDirectory() {
        session.setMode("plan");
        UpdateSessionRequestVO request = new UpdateSessionRequestVO();
        request.readMode(TextNode.valueOf("plan"));
        when(directories.resolve(session.getAgentId())).thenReturn(snapshot());

        service.update(session.getId(), etag(), request);

        verify(directories).resolve(session.getAgentId());
        verifyNoInteractions(models);
    }

    @Test
    void sameModelStillResolvesItsCurrentCapability() {
        UpdateSessionRequestVO request = new UpdateSessionRequestVO();
        request.readModelId(TextNode.valueOf(session.getModelId()));
        AgentDirectorySnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(model.id()).thenReturn(session.getModelId());
        when(model.reasoning()).thenReturn(true);
        when(directories.resolve(session.getAgentId())).thenReturn(snapshot);
        when(models.resolveAvailableModel(snapshot, session.getModelId())).thenReturn(model);

        service.update(session.getId(), etag(), request);

        verify(models).resolveAvailableModel(snapshot, session.getModelId());
    }

    @Test
    void explicitThinkingTrueRejectsUnsupportedTargetAsOneFailedUpdate() {
        UpdateSessionRequestVO request = new UpdateSessionRequestVO();
        request.readModelId(TextNode.valueOf("model-b"));
        request.readThinking(BooleanNode.TRUE);
        AgentDirectorySnapshotDTO snapshot = snapshot();
        Model model = mock(Model.class);
        when(model.id()).thenReturn("model-b");
        when(model.reasoning()).thenReturn(false);
        when(directories.resolve(session.getAgentId())).thenReturn(snapshot);
        when(models.resolveAvailableModel(snapshot, "model-b")).thenReturn(model);

        assertThatThrownBy(() -> service.update(session.getId(), etag(), request))
                .isInstanceOf(RuntimeApiException.class)
                .satisfies(error -> assertThat(((RuntimeApiException) error).errorCode())
                        .isEqualTo(RuntimeErrorCode.THINKING_NOT_SUPPORTED));
        verify(repository, never()).updateSession(any(), any(), any(), any());
    }

    @Test
    void rejectsWildcardWeakAndMultipleEntityTags() {
        UpdateSessionRequestVO request = new UpdateSessionRequestVO();
        request.readDisplayName(TextNode.valueOf("名称"));

        for (String invalid : List.of("*", "W/\"weak\"", "\"one\", \"two\"")) {
            assertThatThrownBy(() -> service.update(session.getId(), invalid, request))
                    .isInstanceOf(RuntimeApiException.class)
                    .satisfies(error -> assertThat(((RuntimeApiException) error).errorCode())
                            .isEqualTo(RuntimeErrorCode.INVALID_SESSION_UPDATE_REQUEST));
        }
    }

    private String etag() {
        return etags.create(session.getId(), session.getResourceVersion());
    }

    private AgentDirectorySnapshotDTO snapshot() {
        Path root = Path.of("/tmp/agent");
        return new AgentDirectorySnapshotDTO(
                session.getAgentId(),
                session.getModelId(),
                List.of(session.getModelId()),
                "build",
                root,
                root.resolve(".campusclaw"));
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO value = new RuntimeSessionDTO();
        value.setId("session-update-test");
        value.setAgentId("agent-update-test");
        value.setModelId("model-a");
        value.setState("idle");
        value.setThinking(false);
        value.setMode("build");
        value.setResourceVersion(3L);
        return value;
    }
}
