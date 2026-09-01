/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;

/**
 * 独立开发 Model Manager 适配器的稳定顺序与防枚举语义测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class CatalogRuntimeModelManagerTest {
    @Test
    void availableModelsKeepAgentConfiguredOrder() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        Model modelA = model("model-a", Provider.OPENAI);
        Model modelB = model("model-b", Provider.ANTHROPIC);
        when(catalog.getAvailableModels()).thenReturn(List.of(modelA, modelB));
        var manager = new CatalogRuntimeModelManager(catalog);
        var snapshot = snapshot(List.of("model-b", "model-missing", "model-a"));

        assertThat(manager.listAvailableModels(snapshot)).containsExactly("model-b", "model-a");
    }

    @Test
    void unavailableSelectionUsesSingleNonEnumeratingError() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        Model modelA = model("model-a", Provider.OPENAI);
        when(catalog.getAvailableModels()).thenReturn(List.of(modelA));
        var manager = new CatalogRuntimeModelManager(catalog);

        assertThatThrownBy(() -> manager.resolveAvailableModel(snapshot(List.of("model-a")), "model-secret"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE));
    }

    @Test
    void qualifiedBindingResolvesToPublicModelId() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        Model modelA = model("model-a", Provider.OPENAI);
        when(catalog.getAvailableModels()).thenReturn(List.of(modelA));
        var manager = new CatalogRuntimeModelManager(catalog);
        var snapshot = snapshot(List.of("openai/model-a"));

        assertThat(manager.resolveDefaultModel(snapshot)).isSameAs(modelA);
        assertThat(manager.listAvailableModels(snapshot)).containsExactly("model-a");
    }

    private static Model model(String id, Provider provider) {
        Model model = mock(Model.class);
        when(model.id()).thenReturn(id);
        when(model.provider()).thenReturn(provider);
        return model;
    }

    private static AgentDirectorySnapshotDTO snapshot(List<String> models) {
        return new AgentDirectorySnapshotDTO(
                "agent-0123456789abcdef0123456789abcdef",
                models.get(0),
                models,
                Path.of("/runtime/agent"),
                Path.of("/runtime/agent/.campusclaw"));
    }
}
