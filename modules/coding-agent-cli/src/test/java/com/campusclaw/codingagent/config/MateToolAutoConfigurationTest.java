/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.ToolCallWithTool;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.codingagent.common.client.HttpMateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.ListMateToolsTool;
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

    private static final String[] CAMPUSMATE_PROPERTIES = {
        "campusmate.base-url=http://campusmate-service:8080",
        "campusmate.endpoints.model-chat-path=/mate-service/v1/LLM/chat",
        "campusmate.endpoints.agent-info-path-template=/mate-service/v1/agents/%s",
        "campusmate.endpoints.agent-runtime-path-template=/mate-service/v1/agents/%s/runtime",
        "campusmate.endpoints.skill-info-path-template=/mate-service/v1/skill/query/%s",
        "campusmate.endpoints.tool-metadata-query-path=/mate-service/v1/runtime/tools/query",
        "campusmate.endpoints.tool-execute-path-template=/mate-service/v1/runtime/tools/%s/execute"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MateToolAutoConfiguration.class))
            .withPropertyValues(CAMPUSMATE_PROPERTIES)
            .withUserConfiguration(MockClientSupport.class);

    @Test
    void enabledByDefaultRegistersFactoryNotSingletonTools() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(com.campusclaw.codingagent.tool.mate.MateToolsetFactory.class);

            // 工具不能是单例 Bean，否则 Session 缓存会跨 Session 泄漏。
            assertThat(context).doesNotHaveBean(CallMateTool.class);
            assertThat(context).doesNotHaveBean(ListMateToolsTool.class);
        });
    }

    @Test
    void factoryPairsShareOneCachePerSessionAndIsolateAcrossSessions() {
        runner.run(context -> {
            var factory = context.getBean(com.campusclaw.codingagent.tool.mate.MateToolsetFactory.class);
            var stateA = factory.createSession(
                    "agent-1", Map.of(), com.campusclaw.codingagent.common.client.mate.MateCredentials.empty());
            var stateB = factory.createSession(
                    "agent-2", Map.of(), com.campusclaw.codingagent.common.client.mate.MateCredentials.empty());
            var listA = stateA.createListTool();
            var callA = stateA.createCallTool();
            var listB = stateB.createListTool();

            assertThat(discoveryOf(listA)).isSameAs(discoveryOf(callA));
            assertThat(discoveryOf(listA)).isNotSameAs(discoveryOf(listB));
            assertThat(listA).isNotSameAs(listB);
        });
    }

    private static com.campusclaw.codingagent.tool.mate.MateToolDiscovery discoveryOf(Object tool) {
        try {
            var field = tool.getClass().getDeclaredField("discovery");
            field.setAccessible(true);
            return (com.campusclaw.codingagent.tool.mate.MateToolDiscovery) field.get(tool);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing discovery field on " + tool.getClass(), e);
        }
    }

    @Test
    void disabledExcludesFactory() {
        runner.withPropertyValues("campusmate.tool.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(com.campusclaw.codingagent.tool.mate.MateToolsetFactory.class);
            assertThat(context.getBeanNamesForType(AgentTool.class)).isEmpty();
        });
    }

    @Test
    void gatewayAddressAndEndpointPathsReachTheClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MateToolAutoConfiguration.class))
                .withPropertyValues(
                        "campusmate.base-url=http://campusmate.example.com:9999",
                        "campusmate.endpoints.model-chat-path=/mate-service/custom/chat",
                        "campusmate.endpoints.agent-info-path-template=/mate-service/custom/agents/%s",
                        "campusmate.endpoints.agent-runtime-path-template=/mate-service/custom/agents/%s/runtime",
                        "campusmate.endpoints.skill-info-path-template=/mate-service/custom/skills/%s",
                        "campusmate.endpoints.tool-metadata-query-path=/mate-service/custom/tools/query",
                        "campusmate.endpoints.tool-execute-path-template=/mate-service/custom/tools/%s/execute")
                .run(context -> {
                    assertThat(context).hasSingleBean(MateToolClient.class);
                    MateToolClient client = context.getBean(MateToolClient.class);
                    assertThat(client)
                            .isInstanceOf(HttpMateToolClient.class)
                            .hasFieldOrPropertyWithValue("campusMateBaseUrl", "http://campusmate.example.com:9999")
                            .hasFieldOrPropertyWithValue("agentInfoPathTemplate", "/mate-service/custom/agents/%s")
                            .hasFieldOrPropertyWithValue("skillInfoPathTemplate", "/mate-service/custom/skills/%s")
                            .hasFieldOrPropertyWithValue("toolMetadataQueryPath", "/mate-service/custom/tools/query")
                            .hasFieldOrPropertyWithValue(
                                    "toolExecutePathTemplate", "/mate-service/custom/tools/%s/execute");
                });
    }

    @Test
    void mateErrorPropagatesThroughPipelineAsIsError() {
        MockMateToolClient client = new MockMateToolClient();
        String toolId = "tool-44444444444444444444444444444444";
        client.addTool(new MateToolMeta(toolId, "Explode", "", Map.of(), Map.of(), false, "allow"));
        client.bindAgent("agent-1", List.of(toolId));
        client.overrideCallResult(new MateToolClient.ToolResult("mate exploded", null, true));
        CallMateTool callMateTool = new com.campusclaw.codingagent.tool.mate.MateToolsetFactory(client)
                .createSession(
                        "agent-1", Map.of(), com.campusclaw.codingagent.common.client.mate.MateCredentials.empty())
                .createCallTool();

        ToolCall toolCall = new ToolCall("call-1", "CallMateTool", Map.of("tool", "Explode"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();

        List<ToolResultMessage> results = pipeline.executeAll(
                List.of(new ToolCallWithTool(toolCall, callMateTool, Map.of("tool", "Explode"))),
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
