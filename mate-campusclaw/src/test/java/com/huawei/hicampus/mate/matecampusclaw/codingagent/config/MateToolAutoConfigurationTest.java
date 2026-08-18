/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolCallWithTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.ListMateTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MockMateToolClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ApplicationContext tests for {@link MateToolAutoConfiguration}: verifies the
 * enable/disable switch registers (or excludes) the two Mate AgentTools, and
 * that a Mate-side error propagates through {@link ToolExecutionPipeline} with
 * {@code isError=true}.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MateToolAutoConfiguration.class))
            .withUserConfiguration(MockClientSupport.class);

    @Test
    void enabledByDefaultRegistersBothTools() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CallMateTool.class);
            assertThat(context).hasSingleBean(ListMateTool.class);
            assertThat(context.getBeanNamesForType(AgentTool.class)).contains("callMateTool", "listMateTool");
        });
    }

    @Test
    void disabledExcludesBothTools() {
        runner.withPropertyValues("mate.tool.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CallMateTool.class);
            assertThat(context).doesNotHaveBean(ListMateTool.class);
            assertThat(context.getBeanNamesForType(AgentTool.class)).isEmpty();
        });
    }

    @Test
    void gatewayAddressReachesTheClient() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MateToolClient.class);
        });
    }

    @Test
    void mateErrorPropagatesThroughPipelineAsIsError() {
        MockMateToolClient client = new MockMateToolClient();
        client.overrideCallResult(new MateToolClient.ToolResult("mate exploded", null, true));
        CallMateTool callMateTool = new CallMateTool(client);

        ToolCall toolCall = new ToolCall("call-1", "callMateTool", Map.of("tool", "boom"));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();

        List<ToolResultMessage> results = pipeline.executeAll(
                List.of(new ToolCallWithTool(toolCall, callMateTool, Map.of("tool", "boom"))),
                ToolExecutionMode.SEQUENTIAL,
                new com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentContext(),
                new com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken(),
                event -> {});

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isError()).isTrue();
    }

    /**
     * Support config providing a mock client bean (overrides the real HTTP stub).
     */
    @Configuration(proxyBeanMethods = false)
    static class MockClientSupport {

        /**
         * Provides a mock Mate client bean for tests.
         *
         * @return the mock client
         */
        @Bean
        MateToolClient mateToolClient() {
            return new MockMateToolClient();
        }
    }
}
