/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import com.campusclaw.codingagent.common.client.HttpMateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.ListMateTool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Mate Tool 客户端及两个 AgentTool，保持默认启用语义，并允许通过
 * {@code mate.tool.enabled=false} 显式关闭。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mate.tool.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MateToolProperties.class)
public class MateToolAutoConfiguration {

    /**
     * 在容器没有自定义 Mate Tool 客户端时创建 HTTP 客户端。
     *
     * @param properties Mate Tool 配置
     * @return Mate Tool HTTP 客户端
     */
    @Bean
    @ConditionalOnMissingBean(MateToolClient.class)
    public MateToolClient mateToolClient(MateToolProperties properties) {
        return new HttpMateToolClient(properties.getBaseUrl());
    }

    /**
     * 创建 callMateTool 工具。
     *
     * @param client Mate Tool 客户端
     * @param properties Mate Tool 配置
     * @return callMateTool 工具
     */
    @Bean
    public CallMateTool callMateTool(MateToolClient client, MateToolProperties properties) {
        MateCredentials credentials = MateCredentials.appKey(properties.getXHwId(), properties.getXHwAppKey());
        return new CallMateTool(client, properties.getApprovalUi(), credentials);
    }

    /**
     * 创建与 callMateTool 共享客户端和元数据缓存的 listMateTool 工具。
     *
     * @param client Mate Tool 客户端
     * @param callMateTool 共享缓存和凭据的 callMateTool 工具
     * @return listMateTool 工具
     */
    @Bean
    public ListMateTool listMateTool(MateToolClient client, CallMateTool callMateTool) {
        return new ListMateTool(client, callMateTool);
    }
}
