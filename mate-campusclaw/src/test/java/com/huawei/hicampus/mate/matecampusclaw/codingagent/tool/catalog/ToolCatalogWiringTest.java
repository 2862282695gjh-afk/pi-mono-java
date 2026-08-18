/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.extension.ExtensionRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.skill.ActivateSkillTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Component-scan wiring test for the tool catalog beans.
 *
 * <p>Unit tests construct these classes directly, so a missing stereotype
 * annotation (for example {@code ExtensionRegistry} without
 * {@code @Component}) only surfaces at application boot. This test scans the
 * real packages to fail fast in the build instead.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
class ToolCatalogWiringTest {

    /**
     * Scans the catalog and extension packages and asserts the context
     * refreshes with all constructor dependencies resolvable.
     */
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

    /**
     * Scans the skill control-tool package together with the catalog packages
     * and asserts {@code activate_skill} is discovered through the Spring
     * source and resolved by the catalog, so sessions pick it up from the
     * unified discovery chain instead of manual construction.
     */
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

    /**
     * Scans the managed-agent runtime package with configuration-property
     * binding enabled, asserting the multi-constructor record binds through
     * its canonical constructor and the client's injection constructor is
     * selected unambiguously.
     */
    @Test
    void managedRuntimeBeansWireUnderComponentScan() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
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

    /** Registers {@link AgentRuntimeProperties} for binding in the test context. */
    @Configuration
    @EnableConfigurationProperties(AgentRuntimeProperties.class)
    static class RuntimePropertiesConfig {}
}
