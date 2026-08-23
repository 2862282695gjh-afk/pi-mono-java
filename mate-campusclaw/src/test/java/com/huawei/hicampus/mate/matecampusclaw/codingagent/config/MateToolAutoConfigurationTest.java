/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolCallWithTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.HttpMateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.ListMateToolsTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MockMateToolClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link MateToolAutoConfiguration} 的应用上下文测试，验证启停开关、配置注入和 Mate 错误传播。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MateToolAutoConfiguration.class))
            .withPropertyValues(
                    "mate.endpoints.agent-info-path-prefix=/mate-service/v1/agents/",
                    "mate.endpoints.skill-tools-query-path-prefix=/mate-service/v1/skill/info/query/",
                    "mate.endpoints.tool-metadata-query-path=/mate-service/v1/runtime/tools/query")
            .withUserConfiguration(MockClientSupport.class);

    @Test
    void enabledByDefaultRegistersFactoryNotSingletonTools() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory.class);

            // 工具不能是单例 Bean，否则 Session 缓存会跨 Session 泄漏。
            assertThat(context).doesNotHaveBean(CallMateTool.class);
            assertThat(context).doesNotHaveBean(ListMateToolsTool.class);
        });
    }

    @Test
    void factoryPairsShareOneCachePerSessionAndIsolateAcrossSessions() {
        runner.run(context -> {
            var factory = context.getBean(com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory.class);
            var stateA = factory.createSession("agent-1", Map.of());
            var stateB = factory.createSession("agent-2", Map.of());
            var listA = stateA.createListTool();
            var callA = stateA.createCallTool();
            var listB = stateB.createListTool();

            assertThat(discoveryOf(listA)).isSameAs(discoveryOf(callA));
            assertThat(discoveryOf(listA)).isNotSameAs(discoveryOf(listB));
            assertThat(listA).isNotSameAs(listB);
        });
    }

    private static com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolDiscovery discoveryOf(Object tool) {
        try {
            var field = tool.getClass().getDeclaredField("discovery");
            field.setAccessible(true);
            return (com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolDiscovery) field.get(tool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing discovery field on " + tool.getClass(), e);
        }
    }

    @Test
    void disabledExcludesFactory() {
        runner.withPropertyValues("mate.tool.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory.class);
            assertThat(context.getBeanNamesForType(AgentTool.class)).isEmpty();
        });
    }

    @Test
    void gatewayAddressAndEndpointPathsReachTheClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MateToolAutoConfiguration.class))
                .withPropertyValues(
                        "mate.innerGWSerive=http://gw.example.com:9999",
                        "mate.endpoints.agent-info-path-prefix=/custom/agents/",
                        "mate.endpoints.skill-tools-query-path-prefix=/custom/skills/",
                        "mate.endpoints.tool-metadata-query-path=/custom/tools/query",
                        "mate.endpoints.tool-execute-path-template=/custom/tools/%s/execute")
                .run(context -> {
                    assertThat(context).hasSingleBean(MateToolClient.class);
                    MateToolClient client = context.getBean(MateToolClient.class);
                    assertThat(client)
                            .isInstanceOf(HttpMateToolClient.class)
                            .hasFieldOrPropertyWithValue("mateInnerGwAddress", "http://gw.example.com:9999")
                            .hasFieldOrPropertyWithValue("agentInfoPathPrefix", "/custom/agents/")
                            .hasFieldOrPropertyWithValue("skillToolsQueryPathPrefix", "/custom/skills/")
                            .hasFieldOrPropertyWithValue("toolMetadataQueryPath", "/custom/tools/query")
                            .hasFieldOrPropertyWithValue("toolExecutePathTemplate", "/custom/tools/%s/execute");
                });
    }

    @Test
    void mateErrorPropagatesThroughPipelineAsIsError() {
        MockMateToolClient client = new MockMateToolClient();
        String toolId = "tool-44444444444444444444444444444444";
        client.addTool(new MateToolMeta(toolId, "Explode", "", Map.of(), Map.of(), false, "allow"));
        client.bindAgent("agent-1", List.of(toolId));
        client.overrideCallResult(new MateToolClient.ToolResult("mate exploded", null, true));
        CallMateTool callMateTool = new com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory(client, null)
                .createSession("agent-1", Map.of())
                .createCallTool();

        ToolCall toolCall = new ToolCall("call-1", "CallMateTool", Map.of("tool", "Explode"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();

        List<ToolResultMessage> results = pipeline.executeAll(
                List.of(new ToolCallWithTool(toolCall, callMateTool, Map.of("tool", "Explode"))),
                new com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentContext(),
                new com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken(),
                event -> {});

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isError()).isTrue();
    }

    /** 提供 Mock 客户端 Bean 并覆盖真实 HTTP 实现的测试配置。 */
    @Configuration(proxyBeanMethods = false)
    static class MockClientSupport {

        /**
         * 创建测试使用的 Mock Mate 客户端。
         *
         * @return Mock Mate 客户端
         */
        @Bean
        MateToolClient mateToolClient() {
            return new MockMateToolClient();
        }
    }
}
