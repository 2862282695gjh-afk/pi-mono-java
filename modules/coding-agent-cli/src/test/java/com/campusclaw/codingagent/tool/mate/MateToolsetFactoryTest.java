/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.codingagent.tool.catalog.ToolSelection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MateToolsetFactory#create(ToolSelection)} 的可见性过滤测试：会话
 * 私有注入不得绕过 include/exclude/noTools 配置（与目录解析语义一致）。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolsetFactoryTest {

    private MateToolsetFactory factory;

    @BeforeEach
    void setUp() {
        factory = new MateToolsetFactory(new NoopMateClient(), null);
    }

    @Test
    void nullSelectionInjectsBothTools() {
        assertThat(factory.create((ToolSelection) null))
                .extracting(AgentTool::name)
                .contains("listMateTool", "callMateTool");
    }

    @Test
    void noToolsInjectsNothing() {
        assertThat(factory.create(new ToolSelection(List.of(), List.of(), true)))
                .isEmpty();
    }

    @Test
    void includeFilterKeepsOnlyListedTools() {
        var tools = factory.create(new ToolSelection(List.of("callMateTool"), List.of(), false));
        assertThat(tools).extracting(AgentTool::name).containsExactly("callMateTool");
    }

    @Test
    void excludingCallMateToolInjectsOnlyListMateTool() {
        var tools = factory.create(new ToolSelection(List.of(), List.of("callMateTool"), false));
        assertThat(tools).extracting(AgentTool::name).containsExactly("listMateTool");
    }

    @Test
    void excludingBothInjectsNoMateTools() {
        var tools = factory.create(new ToolSelection(List.of(), List.of("listMateTool", "callMateTool"), false));
        assertThat(tools).isEmpty();
    }

    @Test
    void includeAndExcludeCombinedExcludesWin() {
        var tools = factory.create(
                new ToolSelection(List.of("listMateTool", "callMateTool"), List.of("callMateTool"), false));
        assertThat(tools).extracting(AgentTool::name).containsExactly("listMateTool");
    }

    @Test
    void filteredPairStillSharesOneSessionCache() {
        var tools = factory.create(new ToolSelection(List.of(), List.of("callMateTool"), false));
        var listTool = (ListMateTool) tools.get(0);

        // 与完整批次同语义:组内(list+call)共享缓存;这里验证 list 自身持有缓存实例。
        assertThat(sessionCacheOf(listTool)).isNotNull();
    }

    private static MateToolSessionCache sessionCacheOf(Object tool) {
        try {
            var field = tool.getClass().getDeclaredField("sessionCache");
            field.setAccessible(true);
            return (MateToolSessionCache) field.get(tool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing sessionCache field", e);
        }
    }

    /** 空实现的 Mate 客户端:本测试不触发网关调用。 */
    private static final class NoopMateClient implements com.campusclaw.codingagent.common.client.mate.MateToolClient {

        @Override
        public List<com.campusclaw.codingagent.common.client.mate.MateToolMeta> listTools(
                String agentId, String skillId) {
            return List.of();
        }

        @Override
        public ToolResult callTool(
                String tool,
                java.util.Map<String, Object> args,
                com.campusclaw.codingagent.common.client.mate.MateCredentials credentials) {
            return new ToolResult("noop", null, false);
        }
    }
}
