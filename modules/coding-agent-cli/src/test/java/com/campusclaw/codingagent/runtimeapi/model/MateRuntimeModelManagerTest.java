/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MateRuntimeModelManagerTest {
    private MateRuntimeModelManager manager;

    private AgentDirectorySnapshotDTO snapshot;

    @BeforeEach
    void setUp() {
        MateModelManagerProperties properties = new MateModelManagerProperties();
        properties.setContextWindow(64_000);
        properties.setMaxOutputTokens(4_096);
        properties.setReasoning(false);
        manager = new MateRuntimeModelManager(properties);
        snapshot = new AgentDirectorySnapshotDTO(
                "agent-1",
                "model-a",
                List.of("model-a", "model-b"),
                Path.of("agent/agent-1"),
                Path.of("agent/agent-1/.campusclaw"));
    }

    @Test
    void resolvesDefaultModelWithCampusMateIdentity() {
        var model = manager.resolveDefaultModel(snapshot);

        assertThat(model.id()).isEqualTo("model-a");
        assertThat(model.provider()).isEqualTo(Provider.CAMPUSMATE);
        assertThat(model.api()).isEqualTo(Api.OPENAI_COMPLETIONS);
        assertThat(model.contextWindow()).isEqualTo(64_000);
        assertThat(model.maxTokens()).isEqualTo(4_096);
        assertThat(model.reasoning()).isFalse();
    }

    @Test
    void resolvesAnotherEnabledModelFromSameSnapshot() {
        var model = manager.resolveModel(snapshot, "model-b");

        assertThat(model.id()).isEqualTo("model-b");
        assertThat(model.provider()).isEqualTo(Provider.CAMPUSMATE);
        assertThat(manager.listAvailableModels(snapshot)).containsExactly("model-a", "model-b");
    }

    @Test
    void preservesSlashAsPartOfOpaqueMateModelId() {
        AgentDirectorySnapshotDTO opaqueSnapshot = new AgentDirectorySnapshotDTO(
                "agent-1",
                "team/model-a",
                List.of("team/model-a"),
                Path.of("agent/agent-1"),
                Path.of("agent/agent-1/.campusclaw"));

        var model = manager.resolveDefaultModel(opaqueSnapshot);

        assertThat(model.id()).isEqualTo("team/model-a");
        assertThat(model.provider()).isEqualTo(Provider.CAMPUSMATE);
    }

    @Test
    void rejectsModelOutsideSnapshot() {
        assertThatThrownBy(() -> manager.resolveModel(snapshot, "model-c"))
                .hasMessage(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED.name());
    }
}
