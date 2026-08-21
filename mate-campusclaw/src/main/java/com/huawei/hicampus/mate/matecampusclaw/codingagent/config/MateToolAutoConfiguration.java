/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.HttpMateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.util.MateRestUtil;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.ListMateTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Mate Tool 客户端及两个 AgentTool，保持默认启用语义，并允许通过
 * {@code mate.tool.enabled=false} 显式关闭。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mate.tool.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MateToolProperties.class)
public class MateToolAutoConfiguration {

    /** Mate 内部网关地址，保持对公司既有 {@code mate.innerGWSerive} 配置名的兼容。 */
    @Value("${mate.innerGWSerive:}")
    private String mateInnerGwAddress;

    /** Agent 元数据查询路径前缀。 */
    @Value("${mate.endpoints.agent-info-path-prefix}")
    private String agentInfoPathPrefix;

    /** Skill 绑定工具查询路径前缀。 */
    @Value("${mate.endpoints.skill-tools-query-path-prefix}")
    private String skillToolsQueryPathPrefix;

    /** 工具元数据批量查询路径。 */
    @Value("${mate.endpoints.tool-metadata-query-path}")
    private String toolMetadataQueryPath;

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
        return new HttpMateToolClient(
                mateInnerGwAddress,
                agentInfoPathPrefix,
                skillToolsQueryPathPrefix,
                toolMetadataQueryPath,
                restUtil,
                mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 创建 callMateTool 工具。
     *
     * @param client Mate Tool 客户端
     * @return callMateTool 工具
     */
    @Bean
    public CallMateTool callMateTool(MateToolClient client) {
        return new CallMateTool(client);
    }

    /**
     * 创建 listMateTool 工具。
     *
     * @param client Mate Tool 客户端
     * @return listMateTool 工具
     */
    @Bean
    public ListMateTool listMateTool(MateToolClient client) {
        return new ListMateTool(client);
    }
}
