/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.ToolCallWithTool;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.codingagent.common.client.HttpMateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.ListMateTool;
import com.campusclaw.codingagent.tool.mate.MockMateToolClient;

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
            assertThat(context).hasSingleBean(com.campusclaw.codingagent.tool.mate.MateToolsetFactory.class);

            // Tools must NOT be singleton beans: the session cache is
            // per-session state and singleton beans would leak across sessions.
            assertThat(context).doesNotHaveBean(CallMateTool.class);
            assertThat(context).doesNotHaveBean(ListMateTool.class);
        });
    }

    @Test
    void factoryPairsShareOneCachePerSessionAndIsolateAcrossSessions() {
        runner.run(context -> {
            var factory = context.getBean(com.campusclaw.codingagent.tool.mate.MateToolsetFactory.class);
            var pairA = factory.create();
            var pairB = factory.create();

            var listA = (ListMateTool) pairA.get(0);
            var callA = (CallMateTool) pairA.get(1);
            var listB = (ListMateTool) pairB.get(0);
            var callB = (CallMateTool) pairB.get(1);

            var cacheOfListA = sessionCacheOf(listA);
            var cacheOfCallA = sessionCacheOf(callA);
            var cacheOfListB = sessionCacheOf(listB);

            // Within one session pair: list and call share the same cache instance.
            assertThat(cacheOfListA).isSameAs(cacheOfCallA);

            // Across sessions: distinct caches and distinct tool instances.
            assertThat(cacheOfListA).isNotSameAs(cacheOfListB);
            assertThat(listA).isNotSameAs(listB);
            assertThat(callA).isNotSameAs(callB);
        });
    }

    private static com.campusclaw.codingagent.tool.mate.MateToolSessionCache sessionCacheOf(Object tool) {
        try {
            var field = tool.getClass().getDeclaredField("sessionCache");
            field.setAccessible(true);
            return (com.campusclaw.codingagent.tool.mate.MateToolSessionCache) field.get(tool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing sessionCache field on " + tool.getClass(), e);
        }
    }

    @Test
    void disabledExcludesFactory() {
        runner.withPropertyValues("mate.tool.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(com.campusclaw.codingagent.tool.mate.MateToolsetFactory.class);
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
        client.overrideCallResult(new MateToolClient.ToolResult("mate exploded", null, true));
        CallMateTool callMateTool =
                new CallMateTool(client, null, new com.campusclaw.codingagent.tool.mate.MateToolSessionCache());

        ToolCall toolCall =
                new ToolCall("call-1", "callMateTool", Map.of("tool", "tool-44444444444444444444444444444444"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();

        List<ToolResultMessage> results = pipeline.executeAll(
                List.of(new ToolCallWithTool(
                        toolCall, callMateTool, Map.of("tool", "tool-44444444444444444444444444444444"))),
                ToolExecutionMode.SEQUENTIAL,
                new com.campusclaw.agent.tool.AgentContext(),
                new com.campusclaw.agent.tool.CancellationToken(),
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
