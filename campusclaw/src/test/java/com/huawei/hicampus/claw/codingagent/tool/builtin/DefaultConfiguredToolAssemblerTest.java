/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.InputModality;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ModelCost;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.workspace.AgentWorkspaceBoundary;
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
    void explicitProfilesShouldKeepConfiguredQuestionTool(@org.junit.jupiter.api.io.TempDir Path agentRoot) {
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of("Read", "AskUserQuestion", "CallMateTool"));
        properties.setCron(List.of("AskUserQuestion", "Read"));
        properties.setChildAgent(List.of("AskUserQuestion", "Read"));
        properties.afterPropertiesSet();
        var assembler = new DefaultConfiguredToolAssembler(properties, StubTool::new);
        PreparedAgentRuntime runtime = prepared(agentRoot);
        AgentWorkspaceBoundary workspace = AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot);

        assertThat(names(assembler, context(runtime, workspace, ToolEntryPoint.RUNTIME)))
                .containsExactly("Read", "AskUserQuestion", "CallMateTool");
        assertThat(names(assembler, context(runtime, workspace, ToolEntryPoint.CRON)))
                .containsExactly("AskUserQuestion", "Read");
        assertThat(names(assembler, context(runtime, workspace, ToolEntryPoint.CHILD_AGENT)))
                .containsExactly("AskUserQuestion", "Read");
    }

    @Test
    void bundledShowcaseProfileShouldDisableQuestionAndAllowConfigurationOverride(
            @org.junit.jupiter.api.io.TempDir Path agentRoot) throws Exception {
        StandardEnvironment environment = showcaseEnvironment();
        PreparedAgentRuntime runtime = prepared(agentRoot);
        AgentWorkspaceBoundary workspace = AgentWorkspaceBoundary.create(runtime.agentId(), agentRoot);
        ToolAssemblyContext context = context(runtime, workspace, ToolEntryPoint.RUNTIME);

        assertThat(names(configuredAssembler(environment), context))
                .contains("CallMateTool")
                .doesNotContain("AskUserQuestion");

        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "external-override", Map.of("campusclaw.tools.runtime", "Read,AskUserQuestion,CallMateTool")));

        assertThat(names(configuredAssembler(environment), context))
                .containsExactly("Read", "AskUserQuestion", "CallMateTool");
    }

    private static StandardEnvironment showcaseEnvironment() throws Exception {
        var environment = new StandardEnvironment();
        var yaml = new ClassPathResource("application.yml");
        var sources = yaml.exists()
                ? new YamlPropertySourceLoader().load("showcase", yaml)
                : new PropertiesPropertySourceLoader()
                        .load("showcase", new ClassPathResource("application.properties"));
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
        String agentId = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
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
                com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptTestFixtures.prompts("system"));
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
