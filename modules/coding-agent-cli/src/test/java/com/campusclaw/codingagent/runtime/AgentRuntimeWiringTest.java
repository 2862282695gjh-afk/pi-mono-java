/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * 受管 Agent 目录运行时的 Spring 装配测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class AgentRuntimeWiringTest {

    @Test
    void managedRuntimeBeansWireUnderComponentScan() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                            "campusmate.runtime.agent-runtime-path-template=/mate-service/v1/agents/%s/runtime",
                            "campusmate.runtime.skill-info-query-path-template=/mate-service/v1/skill/query/%s")
                    .applyTo(context);
            context.scan(AgentRuntimeProperties.class.getPackage().getName());
            context.register(ObjectMapper.class, RuntimePropertiesConfig.class);
            context.refresh();

            AgentRuntimeProperties properties = context.getBean(AgentRuntimeProperties.class);
            assertThat(properties.agentsRoot()).isNotNull();
            assertThat(properties.connectTimeout()).isNotNull();
            assertThat(context.getBean(MateServiceClient.class)).isNotNull();
            assertThat(context.getBean(AgentRuntimeManager.class)).isNotNull();
        }
    }

    /**
     * 在测试上下文中注册受管目录配置。
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/24]
     * @since [br_eCampusCore 26.0.0]
     */
    @Configuration
    @EnableConfigurationProperties(AgentRuntimeProperties.class)
    static class RuntimePropertiesConfig {}
}
