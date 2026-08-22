/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import com.campusclaw.codingagent.common.client.HttpMateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.util.MateRestUtil;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.ListMateTool;
import com.campusclaw.codingagent.tool.mate.MateCredentialResolver;
import com.campusclaw.codingagent.tool.mate.MateToolSessionCache;
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
     * 会话级工具名→标识映射缓存，供两个 Mate 工具共享——listMateTool
     * 刷新的映射必须能被 callMateTool 读到，二者必须持有同一实例。
     *
     * <p>本 Bean 在 Spring 容器内是单例（降级模式：所有会话共享一份
     * 映射，绑定集以最后一次 listMateTool 查询为准）。会话私有部署应在
     * 会话组装点（如 runtime 的 session engine）为每对工具创建独立的
     * {@link MateToolSessionCache} 并传入同一实例，实现按会话隔离。
     *
     * @return 共享的会话缓存实例
     */
    @Bean
    public MateToolSessionCache mateToolSessionCache() {
        return new MateToolSessionCache();
    }

    /**
     * 创建 callMateTool 工具（与 listMateTool 共享会话缓存）。凭据来源取
     * 容器中可选的 {@link MateCredentialResolver} Bean——部署方注册该 Bean
     * 即接通按调用凭据解析（如 Loop 下发的 Authorization）；未注册时工具
     * 仍装配，但每次调用被 fail-closed 拒绝（见 {@code HttpMateToolClient}
     * 的凭据校验），不会发出未认证请求。
     *
     * @param client Mate Tool 客户端
     * @param credentialResolverProvider 凭据解析器提供器；容器无该 Bean 时为空
     * @param sessionCache 与 listMateTool 共享的会话缓存
     * @return callMateTool 工具
     */
    @Bean
    public CallMateTool callMateTool(
            MateToolClient client,
            ObjectProvider<MateCredentialResolver> credentialResolverProvider,
            MateToolSessionCache sessionCache) {
        return new CallMateTool(client, credentialResolverProvider.getIfAvailable(), sessionCache);
    }

    /**
     * 创建 listMateTool 工具（与 callMateTool 共享会话缓存）。
     *
     * @param client Mate Tool 客户端
     * @param sessionCache 与 callMateTool 共享的会话缓存
     * @return listMateTool 工具
     */
    @Bean
    public ListMateTool listMateTool(MateToolClient client, MateToolSessionCache sessionCache) {
        return new ListMateTool(client, sessionCache);
    }
}
