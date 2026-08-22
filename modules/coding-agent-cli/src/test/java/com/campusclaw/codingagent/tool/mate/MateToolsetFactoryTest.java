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
 * 私有注入不得绕过 include/exclude/noTools 配置，且两个 Mate 工具按
 * 原子组注入——过滤结果不能同时包含 listMateTool 与 callMateTool 时整组
 * 不注入（单独的 call 无法刷新缓存，必然全部失败）。
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
    void includingOnlyCallMateToolInjectsNothing() {
        // 单独的 callMateTool 无法刷新 name→id 缓存,整组不注入。
        assertThat(factory.create(new ToolSelection(List.of("callMateTool"), List.of(), false)))
                .isEmpty();
    }

    @Test
    void excludingListMateToolInjectsNothing() {
        // 排除 listMateTool 后 callMateTool 同样失去缓存来源,整组不注入。
        assertThat(factory.create(new ToolSelection(List.of(), List.of("listMateTool"), false)))
                .isEmpty();
    }

    @Test
    void includingOnlyListMateToolInjectsNothing() {
        // 对称:只能发现不能执行的单独 listMateTool 亦无意义。
        assertThat(factory.create(new ToolSelection(List.of("listMateTool"), List.of(), false)))
                .isEmpty();
    }

    @Test
    void excludingBothInjectsNoMateTools() {
        assertThat(factory.create(new ToolSelection(List.of(), List.of("listMateTool", "callMateTool"), false)))
                .isEmpty();
    }

    @Test
    void fullGroupSelectionInjectsPairSharingOneCache() {
        var tools = factory.create(new ToolSelection(List.of("listMateTool", "callMateTool"), List.of(), false));

        assertThat(tools).extracting(AgentTool::name).containsExactlyInAnyOrder("listMateTool", "callMateTool");
        var listTool = (ListMateTool) tools.stream()
                .filter(tool -> tool instanceof ListMateTool)
                .findFirst()
                .orElseThrow();
        var callTool = (CallMateTool) tools.stream()
                .filter(tool -> tool instanceof CallMateTool)
                .findFirst()
                .orElseThrow();
        assertThat(sessionCacheOf(listTool)).isSameAs(sessionCacheOf(callTool));
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
