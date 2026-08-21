/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.codingagent.extension.ExtensionRegistry;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.AgentRuntimeProperties;
import com.campusclaw.codingagent.runtime.MateServiceClient;
import com.campusclaw.codingagent.tool.skill.ActivateSkillTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * 工具目录 Bean 的组件扫描装配测试。
 *
 * <p>单元测试通常直接构造这些类，缺少组件注解等问题只会在应用启动时暴露；本测试扫描真实包并在构建阶段快速失败。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
class ToolCatalogWiringTest {

    /** 扫描目录与扩展包，验证所有构造器依赖均可解析且上下文能够刷新。 */
    @Test
    void catalogAndExtensionBeansWireTogetherUnderComponentScan() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(
                    ToolCatalogWiringTest.class.getPackage().getName(),
                    ExtensionRegistry.class.getPackage().getName());
            context.refresh();

            assertThat(context.getBean(ExtensionRegistry.class)).isNotNull();
            assertThat(context.getBean(ExtensionToolSource.class)).isNotNull();
            assertThat(context.getBean(DefaultToolCatalog.class)).isNotNull();
        }
    }

    /** 扫描 Skill 控制工具与目录包，验证 {@code activate_skill} 经统一 Spring 发现链进入工具目录。 */
    @Test
    void activateSkillToolFlowsThroughCatalogDiscovery() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(
                    ToolCatalogWiringTest.class.getPackage().getName(),
                    ExtensionRegistry.class.getPackage().getName(),
                    ActivateSkillTool.class.getPackage().getName());
            context.refresh();

            assertThat(context.getBean(ActivateSkillTool.class)).isNotNull();
            DefaultToolCatalog catalog = context.getBean(DefaultToolCatalog.class);
            List<AgentTool> resolved = catalog.resolve(ToolSelection.all());
            assertThat(resolved).extracting(AgentTool::name).contains(ActivateSkillTool.NAME);
        }
    }

    /** 扫描托管 Agent 运行时包，验证配置绑定与客户端注入构造器能够明确装配。 */
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

    /** 在测试上下文中注册 {@link AgentRuntimeProperties} 配置绑定。 */
    @Configuration
    @EnableConfigurationProperties(AgentRuntimeProperties.class)
    static class RuntimePropertiesConfig {}
}
