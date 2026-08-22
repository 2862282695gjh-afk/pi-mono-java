/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.DefaultToolCatalog;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.SpringAgentToolSource;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolSelection;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.ListMateTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话私有 Mate 工具在 {@code AgentSession.initialize()} 后仍可见的回归
 * 测试：目录刷新不得丢弃构造传入的会话本地工具对。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
class AgentSessionMateToolsTest {

    @TempDir
    Path tempDir;

    private com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService piAiService;

    private com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry modelRegistry;

    @BeforeEach
    void setUp() {
        piAiService = org.mockito.Mockito.mock(com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService.class);
        modelRegistry = new com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry();
        modelRegistry.register(new com.huawei.hicampus.mate.matecampusclaw.ai.types.Model(
                "claude-sonnet-4-20250514",
                "Claude Sonnet 4",
                com.huawei.hicampus.mate.matecampusclaw.ai.types.Api.ANTHROPIC_MESSAGES,
                com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider.ANTHROPIC,
                "https://api.anthropic.com",
                true,
                List.of(com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality.TEXT, com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality.IMAGE),
                new com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost(3.0, 15.0, 0.3, 3.75),
                200000,
                16000,
                null,
                null,
                null));
    }

    @Test
    void initializeKeepsSessionLocalMateToolsAfterCatalogRefresh() {
        AgentTool catalogTool = new StubLocalTool("bash");
        MateToolsetFactory factory = new MateToolsetFactory(new NoopMateClient(), null);
        List<AgentTool> initialTools = new ArrayList<>();
        initialTools.add(catalogTool);
        initialTools.addAll(factory.create());

        AgentSession session = new AgentSession(
                piAiService,
                modelRegistry,
                new com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.SystemPromptBuilder(),
                new com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader(),
                new com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillExpander(),
                initialTools);
        session.setToolCatalog(
                new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(catalogTool)))), ToolSelection.all());
        session.initialize(new SessionConfig("claude-sonnet-4-20250514", tempDir, null, "interactive"));

        List<AgentTool> visible = session.getAgent().getState().getTools();
        assertThat(visible).extracting(AgentTool::name).contains("bash", "listMateTool", "callMateTool");

        // 会话私有的两个 Mate 工具必须是同一工厂批次(共享缓存)。
        ListMateTool listTool = (ListMateTool) visible.stream()
                .filter(tool -> tool instanceof ListMateTool)
                .findFirst()
                .orElseThrow();
        CallMateTool callTool = (CallMateTool) visible.stream()
                .filter(tool -> tool instanceof CallMateTool)
                .findFirst()
                .orElseThrow();
        assertThat(sessionCacheOf(listTool)).isSameAs(sessionCacheOf(callTool));
    }

    private static com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolSessionCache sessionCacheOf(Object tool) {
        try {
            var field = tool.getClass().getDeclaredField("sessionCache");
            field.setAccessible(true);
            return (com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolSessionCache) field.get(tool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing sessionCache field", e);
        }
    }

    /** 最小桩工具：仅提供名称,执行不被调用。 */
    private static final class StubLocalTool implements AgentTool {

        private final String toolName;

        private StubLocalTool(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String label() {
            return toolName;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public JsonNode parameters() {
            return nullableSchema();
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            throw new UnsupportedOperationException("stub");
        }

        static JsonNode nullableSchema() {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"type\":\"object\"}");
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 空实现的 Mate 客户端:本测试不触发网关调用。 */
    private static final class NoopMateClient implements com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient {

        @Override
        public List<com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta> listTools(
                String agentId, String skillId) {
            return List.of();
        }

        @Override
        public ToolResult callTool(
                String tool,
                Map<String, Object> args,
                com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials credentials) {
            return new ToolResult("noop", null, false);
        }
    }
}
