/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.CredentialMode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;

import org.junit.jupiter.api.Test;

/**
 * 独立开发 Model Manager 适配器的稳定顺序与防枚举语义测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class CatalogRuntimeModelManagerTest {
    @Test
    void availableModelsKeepAgentConfiguredOrder() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        Model modelA = model("model-a");
        Model modelB = model("model-b");
        when(catalog.getAvailableModels()).thenReturn(List.of(modelA, modelB));
        var manager = new CatalogRuntimeModelManager(catalog);
        var snapshot = snapshot(List.of("model-b", "model-missing", "model-a"));

        assertThat(manager.listAvailableModels(snapshot)).containsExactly("model-b", "model-a");
    }

    @Test
    void unavailableSelectionUsesSingleNonEnumeratingError() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        Model modelA = model("model-a");
        when(catalog.getAvailableModels()).thenReturn(List.of(modelA));
        var manager = new CatalogRuntimeModelManager(catalog);

        assertThatThrownBy(() -> manager.resolveAvailableModel(
                        snapshot(List.of("model-a")),
                        new CallerAuthContext("mate-service", CredentialMode.JWT),
                        "model-secret"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE));
    }

    private static Model model(String id) {
        Model model = mock(Model.class);
        when(model.id()).thenReturn(id);
        return model;
    }

    private static AgentRuntimeSnapshotDTO snapshot(List<String> models) {
        return new AgentRuntimeSnapshotDTO(
                "agent_011CZkYqphY8vELVzwCUpqiQ", "revision-1", "model-a", models, Path.of("/runtime/revision-1"));
    }
}
