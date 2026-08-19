/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.SystemPromptBuilder;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.SessionConfig;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillExpander;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransientAgentRunnerTest {

    private AgentRuntimeManager runtimeManager;
    private LocalAgentDispatcher dispatcher;
    private StubSession session;
    private TransientAgentRunner runner;

    @BeforeEach
    void setUp() {
        runtimeManager = mock(AgentRuntimeManager.class);
        dispatcher = mock(LocalAgentDispatcher.class);
        when(dispatcher.runtimeManager()).thenReturn(runtimeManager);
        runner = new TransientAgentRunner() {
            @Override
            AgentSession createSession(
                    DelegationWiring wiring, PreparedAgentRuntime childRuntime, DelegationState childState) {
                return session;
            }
        };
    }

    @Test
    void returnsLastAssistantTextAsChildAnswer() {
        session = stubSession(
                List.of(assistant("draft", StopReason.TOOL_USE), assistant("final answer", StopReason.STOP)));
        when(runtimeManager.sessionConfig(any(), any()))
                .thenReturn(new SessionConfig("glm-5", Path.of("/tmp/a"), "prompt", "one-shot"));

        String answer = runner.run(childRuntime(), childState(), "do it", "fallback");

        assertEquals("final answer", answer);
        assertEquals("do it", session.receivedPrompt);
        assertEquals("glm-5", session.receivedConfig.model());
    }

    @Test
    void surfacesChildErrorStopAsRuntimeException() {
        session = stubSession(List.of(new AssistantMessage(
                List.of(new TextContent("boom")),
                "api",
                "provider",
                "m",
                null,
                null,
                StopReason.ERROR,
                "provider down",
                0L)));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> runner.run(childRuntime(), childState(), "do it", "fallback"));

        assertTrue(error.getMessage().contains("provider down"));
    }

    private static AssistantMessage assistant(String text, StopReason stopReason) {
        return new AssistantMessage(
                List.of(new TextContent(text)), "api", "provider", "m", null, null, stopReason, null, 0L);
    }

    private static PreparedAgentRuntime childRuntime() {
        return new PreparedAgentRuntime(
                "agent-2",
                Path.of("/tmp/agents/agent-2"),
                new MateServiceClient.AgentRuntime(
                        List.of("glm-5"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("d"),
                        "n",
                        Boolean.TRUE,
                        "agent-2",
                        "agent-2",
                        "prompt",
                        List.of("campus"),
                        "1"),
                List.of());
    }

    private DelegationState childState() {
        return DelegationState.childOf(
                DelegationState.entry(
                        dispatcher,
                        "conv",
                        null,
                        new DelegationWiring(
                                mock(CampusClawAiService.class),
                                new ModelRegistry(),
                                mock(SystemPromptBuilder.class),
                                mock(SkillLoader.class),
                                mock(SkillExpander.class),
                                List.of(),
                                null,
                                null)),
                DelegationContext.forEntry("agent-1", "agent-2", "conv", "inv-1"));
    }

    private static StubSession stubSession(List<com.huawei.hicampus.mate.matecampusclaw.ai.types.Message> history) {
        return new StubSession(history);
    }

    private static final class StubSession extends AgentSession {

        private final List<com.huawei.hicampus.mate.matecampusclaw.ai.types.Message> history;
        private String receivedPrompt;
        private SessionConfig receivedConfig;

        StubSession(List<com.huawei.hicampus.mate.matecampusclaw.ai.types.Message> history) {
            super(
                    mock(CampusClawAiService.class),
                    new ModelRegistry(),
                    mock(SystemPromptBuilder.class),
                    mock(SkillLoader.class),
                    mock(SkillExpander.class),
                    List.of());
            this.history = history;
        }

        @Override
        public void initialize(SessionConfig config) {
            this.receivedConfig = config;
        }

        @Override
        public CompletableFuture<Void> prompt(String userInput) {
            this.receivedPrompt = userInput;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public List<com.huawei.hicampus.mate.matecampusclaw.ai.types.Message> getHistory() {
            return history;
        }
    }
}
