/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.ToolCallWithTool;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.ToolResultMessage;
import com.huawei.hicampus.claw.codingagent.common.client.HttpMateToolClient;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.mate.CallTool;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolAdapter;
import com.huawei.hicampus.claw.codingagent.tool.mate.MockMateToolClient;

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
            assertThat(context).hasSingleBean(com.huawei.hicampus.claw.codingagent.tool.mate.MateToolsetFactory.class);

            // 工具不能是单例 Bean，否则 Session 缓存会跨 Session 泄漏。
            assertThat(context).doesNotHaveBean(CallTool.class);
            assertThat(context).doesNotHaveBean(MateToolAdapter.class);
        });
    }

    @Test
    void factoryCreatesSessionPrivateStateAndToolInstances() {
        runner.run(context -> {
            var factory = context.getBean(com.huawei.hicampus.claw.codingagent.tool.mate.MateToolsetFactory.class);
            var stateA = factory.createSession(
                    runtime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", List.of(), List.of()),
                    com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials.empty());
            var stateB = factory.createSession(
                    runtime("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", List.of(), List.of()),
                    com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials.empty());

            assertThat(stateA).isNotSameAs(stateB);
            assertThat(stateA.createCallTool()).isNotSameAs(stateA.createCallTool());
        });
    }

    @Test
    void disabledExcludesFactory() {
        runner.withPropertyValues("campusmate.tool.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(com.huawei.hicampus.claw.codingagent.tool.mate.MateToolsetFactory.class);
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
        String skillId = "skill-55555555555555555555555555555555";
        client.bindSkill(skillId, List.of(toolId));
        client.overrideCallResult(new MateToolClient.ToolResult("mate exploded", null, true));
        BoundTool binding = new BoundTool(null, "Explode", toolId, null, "Explode", "allow", null, null);
        SkillInfo skill = new SkillInfo(
                "managed",
                skillId,
                "1.0.0",
                "Managed",
                null,
                "---\nname: managed\ndescription: Managed\n---\nInstructions",
                List.of(binding),
                List.of(),
                List.of(),
                List.of());
        CallTool callTool = new com.huawei.hicampus.claw.codingagent.tool.mate.MateToolsetFactory(client)
                .createSession(
                        runtime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", List.of(), List.of(skill)),
                        com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials.empty())
                .createCallTool();

        ToolCall toolCall = new ToolCall("call-1", "CallTool", Map.of("tool", "Explode"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();

        List<ToolResultMessage> results = pipeline.executeAll(
                List.of(new ToolCallWithTool(toolCall, callTool, Map.of("tool", "Explode"))),
                new com.huawei.hicampus.claw.agent.tool.AgentContext(),
                new com.huawei.hicampus.claw.agent.tool.CancellationToken(),
                event -> {});

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isError()).isTrue();
    }

    private static PreparedAgentRuntime runtime(String agentId, List<BoundTool> agentTools, List<SkillInfo> skills) {
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        when(runtime.agentId()).thenReturn(agentId);
        when(runtime.metadata()).thenReturn(metadata);
        when(metadata.bindingTools()).thenReturn(agentTools);
        when(runtime.skills()).thenReturn(skills);
        return runtime;
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
