/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi;

import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.FileAgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.event.RandomRuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventProperties;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.campusclaw.codingagent.runtimeapi.model.CatalogRuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.session.RandomSessionIdGenerator;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeCleanupProperties;
import com.campusclaw.codingagent.runtimeapi.session.SessionIdGenerator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runtime HTTP V1 的公共 Bean 和独立开发适配器配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration
@EnableScheduling
@MapperScan(basePackageClasses = RuntimeSessionMapper.class)
@EnableConfigurationProperties({
    RuntimeCleanupProperties.class,
    RuntimeExecutionProperties.class,
    RuntimeEventProperties.class
})
public class RuntimeApiConfiguration {
    @Bean
    @ConditionalOnMissingBean(ResultBeanAdapter.class)
    public ResultBeanAdapter standaloneResultBeanAdapter() {
        return new StandaloneResultBeanAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(AgentDirectoryResolver.class)
    public AgentDirectoryResolver fileAgentDirectoryResolver(AgentRuntimeManager runtimeManager) {
        return new FileAgentDirectoryResolver(runtimeManager);
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
}
