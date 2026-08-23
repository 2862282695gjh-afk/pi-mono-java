/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.HttpMateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.util.MateRestUtil;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateCredentialResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory;
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

    /** 工具执行路径模板。 */
    @Value("${mate.endpoints.tool-execute-path-template:/mate-service/v1/runtime/tools/%s/execute}")
    private String toolExecutePathTemplate;

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
                toolExecutePathTemplate,
                restUtil,
                mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * 创建 Mate 工具对工厂。工具名→标识缓存是会话私有状态（不同 agent
     * 绑定的工具列表不同），因此两个工具与共享缓存必须按会话成组创建：
     * 会话组装点每会话调用一次 {@link MateToolsetFactory#createSession(String, java.util.Map)}，
     * 得到一组持有独立缓存、但彼此共享该缓存的工具实例。凭据解析器取
     * 容器中可选的 {@link MateCredentialResolver} Bean——部署方注册该
     * Bean 即接通按调用凭据解析；未注册时 CallMateTool 以 fail-closed
     * 方式拒绝（见 {@code HttpMateToolClient} 的凭据校验），不会发出
     * 未认证请求。
     *
     * <p>不能把工具实例注册为 Spring 单例 Bean；公共 SessionFactory 必须
     * 为每个 Session 创建独立状态，避免跨 Agent 名称缓存相互覆盖。
     *
     * @param client Mate Tool 客户端
     * @param credentialResolverProvider 凭据解析器提供器；容器无该 Bean 时为空
     * @return 会话私有工具对工厂
     */
    @Bean
    public MateToolsetFactory mateToolsetFactory(
            MateToolClient client, ObjectProvider<MateCredentialResolver> credentialResolverProvider) {
        return new MateToolsetFactory(client, credentialResolverProvider.getIfAvailable());
    }
}
