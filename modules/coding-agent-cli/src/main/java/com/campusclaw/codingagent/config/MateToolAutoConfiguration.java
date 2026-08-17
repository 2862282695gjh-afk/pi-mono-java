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
 * Auto-configuration wiring the Mate tool client and the two Mate AgentTools.
 * Enabled when {@code mate.tool.enabled=true} (default enabled); set to
 * {@code false} to exclude {@code listMateTool}/{@code callMateTool} from the
 * agent tool list.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mate.tool.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MateToolProperties.class)
public class MateToolAutoConfiguration {

    /**
     * Creates the Mate HTTP client when no other bean provides one.
     *
     * @return the HTTP Mate tool client
     */
    @Bean
    @ConditionalOnMissingBean(MateToolClient.class)
    public MateToolClient mateToolClient(MateToolProperties properties) {
        return new HttpMateToolClient(properties.getBaseUrl());
    }

    /**
     * Creates the callMateTool AgentTool bean.
     *
     * @param client the Mate tool client
     * @param properties Mate tool configuration properties
     * @return the CallMateTool bean
     */
    @Bean
    public CallMateTool callMateTool(MateToolClient client, MateToolProperties properties) {
        MateCredentials credentials = MateCredentials.appKey(properties.getXHwId(), properties.getXHwAppKey());
        return new CallMateTool(client, properties.getApprovalUi(), credentials);
    }

    /**
     * Creates the listMateTool AgentTool bean sharing the client and cache with
     * callMateTool.
     *
     * @param client the Mate tool client
     * @param callMateTool the callMateTool bean (shares meta cache and credentials)
     * @return the ListMateTool bean
     */
    @Bean
    public ListMateTool listMateTool(MateToolClient client, CallMateTool callMateTool) {
        return new ListMateTool(client, callMateTool);
    }
}
