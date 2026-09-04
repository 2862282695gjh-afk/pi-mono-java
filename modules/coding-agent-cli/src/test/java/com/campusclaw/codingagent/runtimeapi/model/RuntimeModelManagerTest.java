/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;

/**
 * Runtime 模型可用性校验与异常映射测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeModelManagerTest {
    @Test
    void unavailableResolutionRetainsOriginalFailureAsCause() {
        AgentDirectorySnapshotDTO snapshot = snapshot();
        RuntimeApiException original = new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        RuntimeModelManager manager = mock(RuntimeModelManager.class, CALLS_REAL_METHODS);
        when(manager.listAvailableModels(snapshot)).thenReturn(List.of("model-a"));
        when(manager.resolveModel(snapshot, "model-a")).thenThrow(original);

        assertThatThrownBy(() -> manager.resolveAvailableModel(snapshot, "model-a"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE);
                    assertThat(error.getCause()).isSameAs(original);
                });
    }

    private static AgentDirectorySnapshotDTO snapshot() {
        return new AgentDirectorySnapshotDTO(
                "agent-0123456789abcdef0123456789abcdef",
                "model-a",
                List.of("model-a"),
                Path.of("/runtime/agent"),
                Path.of("/runtime/agent/.campusclaw"));
    }
}
