/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import com.campusclaw.codingagent.common.client.HttpMateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.util.MateRestUtil;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Mate Tool 客户端及两个 AgentTool，保持默认启用语义，并允许通过
 * {@code campusmate.tool.enabled=false} 显式关闭。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "campusmate.tool.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({CampusMateClientProperties.class, MateToolProperties.class})
public class MateToolAutoConfiguration {

    private final CampusMateClientProperties campusMateProperties;

    public MateToolAutoConfiguration(CampusMateClientProperties campusMateProperties) {
        this.campusMateProperties = campusMateProperties;
    }

    /**
     * 创建访问 Mate 内部网关所需的 REST 工具。
     *
     * @return Mate REST 工具
     */
    @Bean
    @ConditionalOnMissingBean
    public MateRestUtil mateRestUtil() {
        return new MateRestUtil();
    }

    /**
     * 在容器没有自定义 Mate Tool 客户端时创建 HTTP 客户端。
     *
     * @param restUtil Mate REST 工具
     * @param mapperProvider Jackson ObjectMapper 提供器；容器没有实例时创建默认实例
     * @return Mate Tool HTTP 客户端
     */
    @Bean
    @ConditionalOnMissingBean(MateToolClient.class)
    public MateToolClient mateToolClient(MateRestUtil restUtil, ObjectProvider<ObjectMapper> mapperProvider) {
        CampusMateClientProperties.Endpoints endpoints = campusMateProperties.endpoints();
        return new HttpMateToolClient(
                campusMateProperties.baseUrl().toString(),
                endpoints.agentInfoPathTemplate(),
                endpoints.skillInfoPathTemplate(),
                endpoints.toolMetadataQueryPath(),
                endpoints.toolExecutePathTemplate(),
                restUtil,
                mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 创建 Mate 工具对工厂。工具名→标识缓存是会话私有状态（不同 agent
     * 绑定的工具列表不同），因此两个工具与共享缓存必须按会话成组创建：
     * 会话组装点每会话调用一次 {@link MateToolsetFactory#createSession(String, java.util.Map,
     * com.campusclaw.codingagent.common.client.mate.MateCredentials)}，得到一组持有独立缓存、
     * 但彼此共享该缓存和执行凭据快照的工具实例。
     *
     * <p>不能把工具实例注册为 Spring 单例 Bean；公共 SessionFactory 必须
     * 为每个 Session 创建独立状态，避免跨 Agent 名称缓存相互覆盖。
     *
     * @param client Mate Tool 客户端
     * @return 会话私有工具对工厂
     */
    @Bean
    public MateToolsetFactory mateToolsetFactory(MateToolClient client) {
        return new MateToolsetFactory(client);
    }
}
