/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * refresh 后下一次执行模型校准的事务输入测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionModelReconcilerTest {
    @Test
    void switchesInvalidCurrentModelAndPersistsOrderedEvents() {
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        AgentDirectoryResolver resolver = mock(AgentDirectoryResolver.class);
        RuntimeModelManager modelManager = mock(RuntimeModelManager.class);
        RuntimeSessionDTO current = session();
        RuntimeSessionDTO updated = session();
        updated.setModelId("model-new");
        updated.setThinking(false);
        updated.setResourceVersion(2L);
        AgentDirectorySnapshotDTO snapshot = snapshot();
        Model fallback = mock(Model.class);
        when(fallback.id()).thenReturn("model-new");
        when(fallback.reasoning()).thenReturn(false);
        when(resolver.resolve(current.getAgentId())).thenReturn(snapshot);
        when(modelManager.resolveAvailableModel(snapshot, "model-old"))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE));
        when(modelManager.resolveAvailableModel(snapshot, "model-new")).thenReturn(fallback);
        when(repository.updateModel(eq(current.getId()), eq(1L), eq("model-new"), eq(false), any(), any()))
                .thenReturn(new SessionConfigurationUpdate(SessionConfigurationUpdate.Status.UPDATED, updated));
        RuntimeSessionModelReconciler reconciler = new RuntimeSessionModelReconciler(
                repository,
                resolver,
                modelManager,
                new RuntimeEntryCodec(
                        new ObjectMapper(),
                        new com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration().messageSource()),
                new SequenceIds(),
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));

        ReconciledRuntimeSession result = reconciler.reconcile(current);

        assertThat(result.session()).isSameAs(updated);
        assertThat(result.configurationEntries())
                .extracting(com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO::getType)
                .containsExactly("session.model.changed", "session.thinking.changed");
        ArgumentCaptor<List<com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO>> entries =
                ArgumentCaptor.forClass(List.class);
        verify(repository)
                .updateModel(eq(current.getId()), eq(1L), eq("model-new"), eq(false), entries.capture(), any());
        assertThat(entries.getValue()).isEqualTo(result.configurationEntries());
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session-reconcile");
        session.setAgentId("agent-reconcile");
        session.setModelId("model-old");
        session.setThinking(true);
        session.setState("idle");
        session.setResourceVersion(1L);
        return session;
    }

    private static AgentDirectorySnapshotDTO snapshot() {
        return new AgentDirectorySnapshotDTO(
                "agent-reconcile", "model-new", List.of("model-new"), Path.of("/agent"), Path.of("/agent/.campusclaw"));
    }

    private static final class SequenceIds
            implements com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator {
        private int sequence;

        @Override
        public String nextId() {
            return "entry-" + sequence++;
        }
    }
}
