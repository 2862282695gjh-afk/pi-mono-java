/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.FileAgentDirectoryResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RandomRuntimeEntryIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEventProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.MateModelManagerProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.MateRuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.ResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RandomSessionIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeCleanupProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.SessionIdGenerator;

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
    RuntimeEventProperties.class,
    MateModelManagerProperties.class
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
    public RuntimeModelManager mateRuntimeModelManager(MateModelManagerProperties properties) {
        return new MateRuntimeModelManager(properties);
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
