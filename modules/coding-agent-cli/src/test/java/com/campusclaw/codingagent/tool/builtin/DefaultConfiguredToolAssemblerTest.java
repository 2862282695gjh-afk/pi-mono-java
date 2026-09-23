/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.campusclaw.codingagent.tool.mate.MockMateToolClient;
import com.campusclaw.codingagent.tool.ops.FindOperations;
import com.campusclaw.codingagent.tool.ops.GrepOperations;
import com.campusclaw.codingagent.tool.ops.LsOperations;
import com.campusclaw.codingagent.tool.ops.ReadOperations;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class DefaultConfiguredToolAssemblerTest {

    @Test
    void createsNewToolsInConfiguredOrderForEverySession(@org.junit.jupiter.api.io.TempDir Path agentRoot)
            throws Exception {
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of("Grep", "Read"));
        properties.afterPropertiesSet();
        var assembler = new DefaultConfiguredToolAssembler(properties, StubTool::new);
        PreparedAgentRuntime runtime = prepared(agentRoot);
        var context = new ToolAssemblyContext(
                ToolEntryPoint.RUNTIME,
                runtime,
                model(),
                ThinkingLevel.OFF,
                AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot),
                Map.of(),
                List.of(),
                null,
                null,
                null);

        List<AgentTool> first = assembler.assemble(ToolEntryPoint.RUNTIME, context);
        List<AgentTool> second = assembler.assemble(ToolEntryPoint.RUNTIME, context);

        assertThat(first).extracting(AgentTool::name).containsExactly("Grep", "Read");
        assertThat(second).extracting(AgentTool::name).containsExactly("Grep", "Read");
        assertThat(first.getFirst()).isNotSameAs(second.getFirst());
        assertThat(first.getLast()).isNotSameAs(second.getLast());
    }

    @Test
    void nonemptyProfileShouldAppendAgentBusinessAdapters(@org.junit.jupiter.api.io.TempDir Path agentRoot) {
        String toolId = "tool-11111111111111111111111111111111";
        BoundTool binding = new BoundTool(null, "Business query", toolId, null, "Query", "allow", null, null);
        PreparedAgentRuntime runtime = prepared(agentRoot, List.of(binding));
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(
                new MateToolMeta(toolId, "Query", "Query records", Map.of("type", "object"), Map.of(), false, "allow"));
        client.bindAgent(runtime.agentId(), List.of(toolId));
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of("Read"));
        properties.afterPropertiesSet();
        var assembler = new DefaultConfiguredToolAssembler(properties, StubTool::new);
        var state = new MateToolsetFactory(client).createSession(runtime, MateCredentials.empty());
        var context = context(agentRoot, runtime, state);

        List<AgentTool> tools = assembler.assemble(ToolEntryPoint.RUNTIME, context);

        assertThat(tools).extracting(AgentTool::name).containsExactly("Read", "Query");
        assertThat(tools.getLast().description()).isEqualTo("Query records");
        assertThat(client.agentListCalls()).isEqualTo(1);
    }

    @Test
    void explicitEmptyProfileShouldSuppressFrameworkAndBusinessTools(@org.junit.jupiter.api.io.TempDir Path agentRoot) {
        String firstId = "tool-11111111111111111111111111111111";
        String secondId = "tool-22222222222222222222222222222222";
        PreparedAgentRuntime runtime = prepared(
                agentRoot,
                List.of(
                        new BoundTool(null, null, firstId, null, "Query", "allow", null, null),
                        new BoundTool(null, null, secondId, null, "Query", "allow", null, null)));
        MockMateToolClient client = new MockMateToolClient();
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of());
        properties.afterPropertiesSet();
        var assembler = new DefaultConfiguredToolAssembler(properties, StubTool::new);
        var state = new MateToolsetFactory(client).createSession(runtime, MateCredentials.empty());

        List<AgentTool> tools = assembler.assemble(ToolEntryPoint.RUNTIME, context(agentRoot, runtime, state));

        assertThat(tools).isEmpty();
        assertThat(client.agentListCalls()).isZero();
    }

    @Test
    void nonInteractiveDefaultsOmitQuestionAndExplicitQuestionReturnsStableUnavailableCode(
            @org.junit.jupiter.api.io.TempDir Path agentRoot) throws Exception {
        var defaults = new BuiltInToolProperties();
        defaults.afterPropertiesSet();
        assertThat(defaults.toolsFor(ToolEntryPoint.CHILD_AGENT)).doesNotContain(BuiltInToolName.ASK_USER_QUESTION);
        assertThat(defaults.toolsFor(ToolEntryPoint.CRON)).doesNotContain(BuiltInToolName.ASK_USER_QUESTION);
        var explicit = new BuiltInToolProperties();
        explicit.setChildAgent(List.of("AskUserQuestion"));
        explicit.setCron(List.of("AskUserQuestion"));
        explicit.afterPropertiesSet();
        var factory = new DefaultBuiltInToolFactory(
                mock(ReadOperations.class),
                mock(FindOperations.class),
                mock(GrepOperations.class),
                mock(LsOperations.class),
                mock(WorkspacePathResolver.class));
        var assembler = new DefaultConfiguredToolAssembler(explicit, factory);
        PreparedAgentRuntime runtime = prepared(agentRoot);
        AgentWorkspaceBoundary workspace = AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot);
        assertUnavailableQuestion(assembler, runtime, workspace, ToolEntryPoint.CHILD_AGENT);
        assertUnavailableQuestion(assembler, runtime, workspace, ToolEntryPoint.CRON);
    }

    private static void assertUnavailableQuestion(
            DefaultConfiguredToolAssembler assembler,
            PreparedAgentRuntime runtime,
            AgentWorkspaceBoundary workspace,
            ToolEntryPoint entryPoint)
            throws Exception {
        ToolAssemblyContext context = new ToolAssemblyContext(
                entryPoint, runtime, model(), ThinkingLevel.OFF, workspace, Map.of(), List.of(), null, null, null);
        AgentTool tool = assembler.assemble(entryPoint, context).getFirst();
        Map<String, Object> arguments = Map.of("questions", List.of(Map.of("id", "q", "question", "Question")));
        var result = new ToolExecutionPipeline()
                .execute(
                        tool,
                        new ToolCall("call-1", "AskUserQuestion", arguments),
                        arguments,
                        new AgentContext(),
                        new CancellationToken(),
                        null);

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("USER_QUESTION_ENTRY_UNSUPPORTED");
        assertThat(result.details()).isEqualTo(Map.of("errorCode", "USER_QUESTION_ENTRY_UNSUPPORTED"));
    }

    @Test
    void explicitProfilesShouldKeepConfiguredQuestionTool(@org.junit.jupiter.api.io.TempDir Path agentRoot) {
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of("Read", "AskUserQuestion", "CallTool"));
        properties.setCron(List.of("AskUserQuestion", "Read"));
        properties.setChildAgent(List.of("AskUserQuestion", "Read"));
        properties.afterPropertiesSet();
        var assembler = new DefaultConfiguredToolAssembler(properties, StubTool::new);
        PreparedAgentRuntime runtime = prepared(agentRoot);
        AgentWorkspaceBoundary workspace = AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot);

        assertThat(names(assembler, context(runtime, workspace, ToolEntryPoint.RUNTIME)))
                .containsExactly("Read", "AskUserQuestion", "CallTool");
        assertThat(names(assembler, context(runtime, workspace, ToolEntryPoint.CRON)))
                .containsExactly("AskUserQuestion", "Read");
        assertThat(names(assembler, context(runtime, workspace, ToolEntryPoint.CHILD_AGENT)))
                .containsExactly("AskUserQuestion", "Read");
    }

    @Test
    void bundledBusinessConfirmationProfileShouldDisableQuestionAndAllowConfigurationOverride(
            @org.junit.jupiter.api.io.TempDir Path agentRoot) throws Exception {
        StandardEnvironment environment = businessConfirmationEnvironment();
        PreparedAgentRuntime runtime = prepared(agentRoot);
        AgentWorkspaceBoundary workspace = AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot);
        ToolAssemblyContext context = context(runtime, workspace, ToolEntryPoint.RUNTIME);

        assertThat(names(configuredAssembler(environment), context))
                .contains("CallTool")
                .doesNotContain("AskUserQuestion");

        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "external-override", Map.of("campusclaw.tools.runtime", "Read,AskUserQuestion,CallTool")));

        assertThat(names(configuredAssembler(environment), context))
                .containsExactly("Read", "AskUserQuestion", "CallTool");
    }

    private static StandardEnvironment businessConfirmationEnvironment() throws Exception {
        var environment = new StandardEnvironment();
        var yaml = new ClassPathResource("application.yml");
        var sources = yaml.exists()
                ? new YamlPropertySourceLoader().load("businessConfirmation", yaml)
                : new PropertiesPropertySourceLoader()
                        .load("businessConfirmation", new ClassPathResource("application.properties"));
        sources.forEach(source -> environment.getPropertySources().addFirst(source));
        return environment;
    }

    private static DefaultConfiguredToolAssembler configuredAssembler(StandardEnvironment environment) {
        BuiltInToolProperties properties = Binder.get(environment)
                .bind("campusclaw.tools", Bindable.of(BuiltInToolProperties.class))
                .get();
        properties.afterPropertiesSet();
        return new DefaultConfiguredToolAssembler(properties, StubTool::new);
    }

    private static ToolAssemblyContext context(
            PreparedAgentRuntime runtime, AgentWorkspaceBoundary workspace, ToolEntryPoint entryPoint) {
        return new ToolAssemblyContext(
                entryPoint, runtime, model(), ThinkingLevel.OFF, workspace, Map.of(), List.of(), null, null, null);
    }

    private static List<String> names(DefaultConfiguredToolAssembler assembler, ToolAssemblyContext context) {
        return assembler.assemble(context.entryPoint(), context).stream()
                .map(AgentTool::name)
                .toList();
    }

    private static PreparedAgentRuntime prepared(Path agentRoot) {
        return prepared(agentRoot, List.of());
    }

    private static PreparedAgentRuntime prepared(Path agentRoot, List<BoundTool> bindings) {
        String agentId = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                bindings,
                List.of(),
                List.of(),
                "Agent",
                true,
                agentId,
                "agent-a",
                "prompt",
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(
                agentId,
                agentRoot,
                metadata,
                List.of(),
                com.campusclaw.codingagent.runtimeapi.agent.AgentPromptTestFixtures.prompts("system"));
    }

    private static ToolAssemblyContext context(
            Path agentRoot,
            PreparedAgentRuntime runtime,
            com.campusclaw.codingagent.tool.mate.MateToolSessionState state) {
        return new ToolAssemblyContext(
                ToolEntryPoint.RUNTIME,
                runtime,
                model(),
                ThinkingLevel.OFF,
                AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot),
                Map.of(),
                List.of(),
                state,
                null,
                null);
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

        private final BuiltInToolName name;

        private StubTool(BuiltInToolName name, ToolAssemblyContext context) {
            this.name = name;
        }

        @Override
        public String name() {
            return name.externalName();
        }

        @Override
        public String label() {
            return name();
        }

        @Override
        public String description() {
            return name();
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
