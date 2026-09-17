/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.stream.AssistantMessageEventStream;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO.PromptSource;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.campusclaw.codingagent.session.compaction.SessionCompactor;
import com.campusclaw.codingagent.session.compaction.SessionCompactor.PreparedCompaction;
import com.campusclaw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.mate.MateToolSessionState;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class AgentSessionFactoryTest {

    private static final String COMPLETE_BASE =
            "Identity.\n\nCommon behavior one.\n\nCommon behavior two.\n\nCommon behavior three.";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsMissingPreparedRuntime() {
        AgentRuntimeManager runtimeManager = mock(AgentRuntimeManager.class);
        AgentSessionFactory factory = new AgentSessionFactory(
                mock(CampusClawAiService.class),
                runtimeManager,
                mock(ConfiguredToolAssembler.class),
                mock(ObjectProvider.class),
                mock(com.campusclaw.codingagent.session.compaction.SessionCompactor.class),
                new PlanPolicyPromptComposer());
        ManagedAgentSessionRequest request = mock(ManagedAgentSessionRequest.class);
        when(request.agentId()).thenReturn("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.create(request))
                .withMessage("Prepared Agent runtime is unavailable");
    }

    @Test
    void createsIsolatedCommonSessionsWithConfiguredEntryPoint() throws Exception {
        Path agentRoot = Files.createDirectory(temporaryDirectory.resolve("agent-a"));
        Path managed = Files.createDirectory(agentRoot.resolve(".campusclaw"));
        Files.writeString(managed.resolve("SYSTEM.md"), "system prompt", StandardCharsets.UTF_8);
        PreparedAgentRuntime runtime = prepared(agentRoot);
        ConfiguredToolAssembler assembler = mock(ConfiguredToolAssembler.class);
        AtomicInteger sequence = new AtomicInteger();
        when(assembler.assemble(eq(ToolEntryPoint.RUNTIME), any()))
                .thenAnswer(invocation -> List.of(new StubTool("Read-" + sequence.incrementAndGet())));
        @SuppressWarnings("unchecked")
        ObjectProvider<MateToolsetFactory> provider = mock(ObjectProvider.class);
        MateToolsetFactory mateToolsetFactory = mock(MateToolsetFactory.class);
        when(provider.getIfAvailable()).thenReturn(mateToolsetFactory);
        AgentRuntimeManager runtimeManager = mock(AgentRuntimeManager.class);
        when(runtimeManager.prepare(runtime.agentId())).thenReturn(runtime);
        AgentSessionFactory factory = new AgentSessionFactory(
                mock(CampusClawAiService.class),
                runtimeManager,
                assembler,
                provider,
                mock(com.campusclaw.codingagent.session.compaction.SessionCompactor.class),
                new PlanPolicyPromptComposer());
        MateCredentials credentials = MateCredentials.jwt("caller-1", "token-1", "access-token-1");
        when(mateToolsetFactory.createSession(runtime.agentId(), Map.of(), credentials))
                .thenReturn(mock(MateToolSessionState.class));
        ManagedAgentSessionRequest request = new ManagedAgentSessionRequest(
                runtime.agentId(),
                ToolEntryPoint.RUNTIME,
                ignored -> model(),
                ThinkingLevel.MEDIUM,
                credentials,
                null,
                null,
                null,
                List.of(),
                List.of());

        ManagedAgentSession first = factory.create(request);
        ManagedAgentSession second = factory.create(request);

        assertThat(first).isNotSameAs(second);
        assertThat(first.agent()).isNotSameAs(second.agent());
        assertThat(first.tools().getFirst()).isNotSameAs(second.tools().getFirst());
        assertBuildPrompt(first);
        assertThat(first.agent().getState().getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        verify(assembler, org.mockito.Mockito.times(2)).assemble(eq(ToolEntryPoint.RUNTIME), any());
        verify(runtimeManager, org.mockito.Mockito.times(2)).prepare(runtime.agentId());
        verify(mateToolsetFactory, org.mockito.Mockito.times(2))
                .createSession(runtime.agentId(), Map.of(), credentials);
    }

    @Test
    void providerRequestKeepsPreparedBuildSnapshotAfterDiskChangeRepeatedApplyAndCompaction() throws Exception {
        Path agentRoot = Files.createDirectory(temporaryDirectory.resolve("fixed-agent"));
        Path managed = Files.createDirectory(agentRoot.resolve(".campusclaw"));
        AgentPromptSnapshotDTO prompts = new AgentPromptSnapshotDTO(
                COMPLETE_BASE, "# Fixed Plan", "# Fixed Build", PromptSource.AGENT_FILE, PromptSource.AGENT_FILE);
        PreparedAgentRuntime runtime = prepared(agentRoot, prompts);
        AgentRuntimeManager runtimes = mock(AgentRuntimeManager.class);
        when(runtimes.prepare(runtime.agentId())).thenReturn(runtime);
        ConfiguredToolAssembler assembler = mock(ConfiguredToolAssembler.class);
        when(assembler.assemble(eq(ToolEntryPoint.RUNTIME), any())).thenReturn(List.of());
        CampusClawAiService aiService = mock(CampusClawAiService.class);
        SessionCompactor compactor = successfulCompactor();
        @SuppressWarnings("unchecked")
        ObjectProvider<MateToolsetFactory> provider = mock(ObjectProvider.class);
        AgentSessionFactory factory = new AgentSessionFactory(
                aiService, runtimes, assembler, provider, compactor, new PlanPolicyPromptComposer());
        ManagedAgentSession session = factory.create(request(runtime.agentId(), ToolEntryPoint.RUNTIME, "plan"));

        Files.writeString(managed.resolve("BUILD.md"), "# Changed Build", StandardCharsets.UTF_8);
        session.applyMode("build");
        session.applyMode("build");
        session.compact(null).get(2L, TimeUnit.SECONDS);
        when(aiService.streamSimple(any(), any(), any())).thenAnswer(call -> completedStream(call.getArgument(0)));
        session.agent().prompt(new UserMessage("continue", 10L)).get(2L, TimeUnit.SECONDS);

        ArgumentCaptor<Context> context = ArgumentCaptor.forClass(Context.class);
        verify(aiService).streamSimple(any(), context.capture(), any());
        assertThat(context.getValue().systemPrompt())
                .isEqualTo(COMPLETE_BASE + "\n\n当前执行角色：Main Agent。\n\n# Fixed Build")
                .containsOnlyOnce(COMPLETE_BASE)
                .containsOnlyOnce("# Fixed Build")
                .doesNotContain("# Fixed Plan", "# Changed Build");
    }

    @Test
    void childPlanUsesItsOwnPromptSnapshotInsteadOfParentOverride() throws Exception {
        Path parentRoot = Files.createDirectory(temporaryDirectory.resolve("parent"));
        Path childRoot = Files.createDirectory(temporaryDirectory.resolve("child"));
        PreparedAgentRuntime parent = prepared(
                parentRoot,
                new AgentPromptSnapshotDTO(
                        "Parent Base",
                        "# Parent Plan",
                        "# Parent Build",
                        PromptSource.AGENT_FILE,
                        PromptSource.AGENT_FILE));
        PreparedAgentRuntime child = prepared(
                childRoot,
                new AgentPromptSnapshotDTO(
                        "Child Base",
                        "# Child Plan",
                        "# Child Build",
                        PromptSource.AGENT_FILE,
                        PromptSource.AGENT_FILE));
        AgentRuntimeManager runtimes = mock(AgentRuntimeManager.class);
        when(runtimes.prepare(parent.agentId())).thenReturn(parent);
        when(runtimes.prepare(child.agentId())).thenReturn(child);
        ConfiguredToolAssembler assembler = mock(ConfiguredToolAssembler.class);
        when(assembler.assemble(any(), any())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<MateToolsetFactory> provider = mock(ObjectProvider.class);
        AgentSessionFactory factory = new AgentSessionFactory(
                mock(CampusClawAiService.class),
                runtimes,
                assembler,
                provider,
                mock(SessionCompactor.class),
                new PlanPolicyPromptComposer());

        ManagedAgentSession childSession = factory.create(request(child.agentId(), ToolEntryPoint.CHILD_AGENT, "plan"));

        assertThat(childSession.agent().getState().getSystemPrompt())
                .isEqualTo("Child Base\n\n当前执行角色：Subagent；只向父 Agent 返回结果。\n\n# Child Plan")
                .doesNotContain("Parent Base", "# Parent Plan");
    }

    private PreparedAgentRuntime prepared(Path agentRoot) {
        String id = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                id,
                "agent-a",
                "prompt",
                List.of(),
                "1.0.0");
        AgentPromptSnapshotDTO prompts = new RuntimeAgentPromptLoader().loadSnapshot(agentRoot.resolve(".campusclaw"));
        return new PreparedAgentRuntime(id, agentRoot, metadata, List.of(), prompts);
    }

    private PreparedAgentRuntime prepared(Path agentRoot, AgentPromptSnapshotDTO prompts) {
        String id = "agent-" + (agentRoot.getFileName().toString().startsWith("child") ? "b" : "a").repeat(32);
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                id,
                agentRoot.getFileName().toString(),
                prompts.basePrompt(),
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(id, agentRoot, metadata, List.of(), prompts);
    }

    private static ManagedAgentSessionRequest request(String agentId, ToolEntryPoint entryPoint, String mode) {
        return new ManagedAgentSessionRequest(
                agentId,
                entryPoint,
                ignored -> model(),
                ThinkingLevel.MEDIUM,
                MateCredentials.empty(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                mode,
                null,
                null);
    }

    private static SessionCompactor successfulCompactor() {
        SessionCompactor compactor = mock(SessionCompactor.class);
        PreparedCompaction prepared =
                new PreparedCompaction(List.of(), List.of(), List.of(), 0, null, Set.of(), 0, false);
        SessionCompactionResult result = new SessionCompactionResult("summary", List.of(), 0, 0, 0, Usage.empty());
        when(compactor.prepare(any())).thenReturn(prepared);
        when(compactor.compact(eq(prepared), any(), any(), eq(null)))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(compactor.compactedMessages(result)).thenReturn(List.of());
        return compactor;
    }

    private static AssistantMessageEventStream completedStream(Model model) {
        AssistantMessage response = new AssistantMessage(
                List.of(new TextContent("done")),
                model.api().value(),
                model.provider().value(),
                model.id(),
                null,
                Usage.empty(),
                StopReason.STOP,
                null,
                11L);
        AssistantMessageEventStream stream = new AssistantMessageEventStream();
        stream.end(response);
        return stream;
    }

    private static void assertBuildPrompt(ManagedAgentSession session) {
        assertThat(session.agent().getState().getSystemPrompt())
                .startsWith("system prompt\n\n当前执行角色：Main Agent。\n\n# Build 模式")
                .contains("你当前处于 build 模式")
                .doesNotContain("# Plan 模式");
    }

    private static Model model() {
        return new Model(
                "model-a",
                "Model A",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                1000,
                100,
                null,
                null,
                null);
    }

    private static final class StubTool implements AgentTool {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final String name;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            return new AgentToolResult(List.of(), null);
        }
    }
}
