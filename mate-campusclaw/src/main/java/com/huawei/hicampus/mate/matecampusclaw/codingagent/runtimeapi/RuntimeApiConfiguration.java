/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeAgentAuthorizer;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeAuthProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeCredentialVerifier;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.StandaloneCredentialVerifier;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.StandaloneRuntimeAgentAuthorizer;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RandomRuntimeEntryIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEventProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeFileResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.StandaloneRuntimeFileResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.CatalogRuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RandomSessionIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeCleanupProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.SessionIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.FileAgentRuntimeSnapshotProvider;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.RuntimeTemplateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runtime HTTP V1 的公共 Bean 和独立开发适配器配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    RuntimeAuthProperties.class,
    RuntimeTemplateProperties.class,
    RuntimeCleanupProperties.class,
    RuntimeEventProperties.class
})
public class RuntimeApiConfiguration {
    @Bean
    @ConditionalOnMissingBean(RuntimeCredentialVerifier.class)
    public RuntimeCredentialVerifier standaloneCredentialVerifier(RuntimeAuthProperties properties) {
        return new StandaloneCredentialVerifier(properties);
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeAgentAuthorizer.class)
    public RuntimeAgentAuthorizer standaloneRuntimeAgentAuthorizer() {
        return new StandaloneRuntimeAgentAuthorizer();
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntimeSnapshotProvider.class)
    public AgentRuntimeSnapshotProvider fileAgentRuntimeSnapshotProvider(
            RuntimeTemplateProperties properties, ObjectMapper objectMapper) {
        return new FileAgentRuntimeSnapshotProvider(properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeModelManager.class)
    public RuntimeModelManager catalogRuntimeModelManager(ModelCatalogService modelCatalogService) {
        return new CatalogRuntimeModelManager(modelCatalogService);
    }

    @Bean
    @ConditionalOnMissingBean(SessionIdGenerator.class)
    public SessionIdGenerator randomSessionIdGenerator() {
        return new RandomSessionIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeEntryIdGenerator.class)
    public RuntimeEntryIdGenerator randomRuntimeEntryIdGenerator() {
        return new RandomRuntimeEntryIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeFileResolver.class)
    public RuntimeFileResolver standaloneRuntimeFileResolver() {
        return new StandaloneRuntimeFileResolver();
    }
}
