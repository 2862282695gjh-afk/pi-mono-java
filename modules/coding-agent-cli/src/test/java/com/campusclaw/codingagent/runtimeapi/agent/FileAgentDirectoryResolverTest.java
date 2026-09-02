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
import com.campusclaw.codingagent.test.Log4j2TestAppender;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void logsAndTranslatesRuntimePreparationFailure() {
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        AgentRuntimeException failure = new AgentRuntimeException("Mate unavailable");
        when(manager.prepare(AGENT_ID)).thenThrow(failure);
        Logger logger = (Logger) LogManager.getLogger(FileAgentDirectoryResolver.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("agent-directory-failure-logs");
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> new FileAgentDirectoryResolver(manager).resolve(AGENT_ID))
                    .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
                        assertThat(error).hasNoCause();
                    });
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage())
                        .contains("operation=runtime.agent.prepare")
                        .contains("errorCode=AGENT_NOT_AVAILABLE");
                String loggedAgentId = event.getContextData().getValue("agentId");
                assertThat(loggedAgentId).isEqualTo(AGENT_ID);
                assertThat(event.getThrown()).isSameAs(failure);
            });
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }
    }

    @Test
    void invalidMateResponseMapsToAgentNotAvailableWithStableCode() {
        // result 缺失/非对象等响应解析失败映射为 AGENT_NOT_AVAILABLE，
        // 诊断异常只写入日志，公开边界按错误码渲染中英文文案。
        AgentRuntimeManager manager = mock(AgentRuntimeManager.class);
        AgentRuntimeException failure = new AgentRuntimeException(
                AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, "querySkillInfo result must be an object");
        when(manager.prepare(AGENT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> new FileAgentDirectoryResolver(manager).resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
                    assertThat(error).hasNoCause();
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
