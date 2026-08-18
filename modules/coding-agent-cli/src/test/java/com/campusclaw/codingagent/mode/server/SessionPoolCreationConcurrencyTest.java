/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.mode.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.codingagent.prompt.SystemPromptBuilder;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.session.SessionConfig;
import com.campusclaw.codingagent.tool.catalog.ToolSelection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrency tests for {@link SessionPool} session creation: remote Agent
 * preparation runs outside the sessions map lock, so one slow conversation
 * never serializes another, and concurrent creation for the same conversation
 * is deduplicated to a single session.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class SessionPoolCreationConcurrencyTest {

    @TempDir
    Path tempDir;

    private CampusClawAiService aiService;
    private SystemPromptBuilder promptBuilder;
    private ModelRegistry modelRegistry;

    @BeforeEach
    void setUp() {
        aiService = mock(CampusClawAiService.class);
        promptBuilder = mock(SystemPromptBuilder.class);
        when(promptBuilder.build(any())).thenReturn("prompt");
        modelRegistry = new ModelRegistry();
        modelRegistry.register(new Model(
                "gpt-4o",
                "GPT-4o",
                Api.OPENAI_RESPONSES,
                Provider.OPENAI,
                "https://api.openai.com",
                false,
                List.of(InputModality.TEXT, InputModality.IMAGE),
                new ModelCost(2.5, 10.0, 1.25, 2.5),
                128000,
                16384,
                null,
                null,
                null));
    }

    @Test
    void blockedCreationOfOneConversationDoesNotSerializeAnother() throws Exception {
        CountDownLatch agentAEntered = new CountDownLatch(1);
        CountDownLatch releaseAgentA = new CountDownLatch(1);
        AgentRuntimeManager runtimeManager = mock(AgentRuntimeManager.class);
        when(runtimeManager.prepare("agent-a")).thenAnswer(invocation -> {
            agentAEntered.countDown();
            releaseAgentA.await();
            return preparedRuntime("agent-a");
        });
        when(runtimeManager.prepare("agent-b")).thenReturn(preparedRuntime("agent-b"));
        when(runtimeManager.sessionConfig(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        SessionPool pool = poolWith(runtimeManager);

        var first = CompletableFuture.supplyAsync(() -> pool.getOrCreate("agent-a", "conv-a"));
        try {
            assertThat(agentAEntered.await(5, TimeUnit.SECONDS)).isTrue();

            SessionPool.SessionRef second = pool.getOrCreate("agent-b", "conv-b");

            assertThat(second.conversationId()).isEqualTo("conv-b");
        } finally {
            releaseAgentA.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS).conversationId()).isEqualTo("conv-a");
    }

    @Test
    void concurrentCreationForTheSameConversationIsDeduplicated() throws Exception {
        CountDownLatch agentAEntered = new CountDownLatch(1);
        CountDownLatch releaseAgentA = new CountDownLatch(1);
        AgentRuntimeManager runtimeManager = mock(AgentRuntimeManager.class);
        when(runtimeManager.prepare("agent-a")).thenAnswer(invocation -> {
            agentAEntered.countDown();
            releaseAgentA.await();
            return preparedRuntime("agent-a");
        });
        when(runtimeManager.sessionConfig(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        SessionPool pool = poolWith(runtimeManager);

        var first = CompletableFuture.supplyAsync(() -> pool.getOrCreate("agent-a", "conv-a"));
        assertThat(agentAEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> pool.getOrCreate("agent-a", "conv-a"));
        try {
            assertThat(second.isDone()).isFalse();

            releaseAgentA.countDown();

            SessionPool.SessionRef firstRef = first.get(5, TimeUnit.SECONDS);
            SessionPool.SessionRef secondRef = second.get(5, TimeUnit.SECONDS);
            assertThat(secondRef.session()).isSameAs(firstRef.session());
        } finally {
            releaseAgentA.countDown();
        }
        verify(runtimeManager, times(1)).prepare("agent-a");
    }

    private SessionPool poolWith(AgentRuntimeManager runtimeManager) {
        return new SessionPool(
                aiService,
                modelRegistry,
                promptBuilder,
                List.of(),
                null,
                ToolSelection.all(),
                new SessionConfig("gpt-4o", tempDir, null, "server"),
                null,
                false,
                false,
                null,
                runtimeManager,
                null);
    }

    private PreparedAgentRuntime preparedRuntime(String agentId) {
        AgentRuntime metadata = new AgentRuntime(
                List.of("gpt-4o"),
                List.of(),
                List.of(),
                List.of("Agent description"),
                "Agent " + agentId,
                agentId,
                agentId,
                "Agent system prompt",
                List.of(),
                "1",
                null);
        return new PreparedAgentRuntime(agentId, tempDir.resolve(agentId + "-root"), metadata, List.of());
    }
}
