/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.campusclaw.codingagent.runtime.AgentRuntimeErrorCode;
import com.campusclaw.codingagent.runtime.AgentRuntimeException;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FileAgentDirectoryResolverTest {

    private static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesManagedRuntimeAndUsesItsModelBinding() {
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        when(manager.prepare(AGENT_ID)).thenReturn(prepared(List.of("model-a", "model-b")));

        AgentDirectorySnapshotDTO snapshot = new FileAgentDirectoryResolver(manager).resolve(AGENT_ID);

        assertThat(snapshot.agentRoot()).isEqualTo(temporaryDirectory);
        assertThat(snapshot.runtimeDirectory()).isEqualTo(temporaryDirectory.resolve(".campusclaw"));
        assertThat(snapshot.defaultModelId()).isEqualTo("model-a");
        assertThat(snapshot.enabledModelIds()).containsExactly("model-a", "model-b");
        verify(manager).prepare(AGENT_ID);
    }

    @Test
    void rejectsMissingOrDuplicateModelBinding() {
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        when(manager.prepare(AGENT_ID)).thenReturn(prepared(List.of("model-a", "model-a")));

        assertThatThrownBy(() -> new FileAgentDirectoryResolver(manager).resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
    }

    @Test
    void logsAndTranslatesRuntimePreparationFailure(CapturedOutput output) {
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        AgentRuntimeException failure = new AgentRuntimeException("Mate unavailable");
        when(manager.prepare(AGENT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> new FileAgentDirectoryResolver(manager).resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
                    assertThat(error).hasNoCause();
                });
        assertThat(output)
                .contains("operation=runtime.agent.prepare")
                .contains("errorCode=AGENT_NOT_AVAILABLE")
                .contains("agentId=\"" + AGENT_ID + "\"")
                .contains("AgentRuntimeException: Mate unavailable");
    }

    @Test
    void invalidMateResponseMapsToAgentNotAvailableWithStableCode() {
        // result 缺失/非对象等响应解析失败映射为 AGENT_NOT_AVAILABLE,
        // 英文诊断只留在 cause,公开边界按错误码渲染中英文文案。
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        AgentRuntimeException failure = new AgentRuntimeException(
                AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, "querySkillInfo result must be an object");
        when(manager.prepare(AGENT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> new FileAgentDirectoryResolver(manager).resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
                    assertThat(error).hasCause(failure);
                    assertThat(failure.stableErrorCode()).isEqualTo("MATE_RESPONSE_INVALID");
                });
    }

    @Test
    void rejectsDisabledManagedAgent() {
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        when(manager.prepare(AGENT_ID)).thenReturn(prepared(List.of("model-a"), false));

        assertThatThrownBy(() -> new FileAgentDirectoryResolver(manager).resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE));
    }

    private PreparedAgentRuntime prepared(List<String> models) {
        return prepared(models, true);
    }

    private PreparedAgentRuntime prepared(List<String> models, boolean enabled) {
        var runtime = new AgentRuntime(
                models, List.of(), List.of(), List.of(), List.of(), "Agent", enabled, AGENT_ID, "agent", "prompt",
                List.of(), "1.0.0");
        return new PreparedAgentRuntime(AGENT_ID, temporaryDirectory, runtime, List.of());
    }
}
