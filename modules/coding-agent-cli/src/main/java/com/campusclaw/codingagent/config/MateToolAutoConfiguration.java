/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import com.campusclaw.codingagent.common.client.HttpMateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.util.MateRestUtil;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.ListMateTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
     * Address of the Mate inner gateway service, from the
     * {@code mate.innerGWSerive} property/environment variable.
     */
    @Value("${mate.innerGWSerive:}")
    private String mateInnerGwAddress;

    /**
     * Creates the REST helper bean used by the Mate tool client to call the
     * inner gateway.
     *
     * @return the MateRestUtil bean
     */
    @Bean
    @ConditionalOnMissingBean
    public MateRestUtil mateRestUtil() {
        return new MateRestUtil();
    }

    /**
     * Creates the Mate HTTP client when no other bean provides one.
     *
     * @param restUtil the Mate REST helper for real gateway calls
     * @param mapperProvider provider for a Jackson mapper; a new one when no
     *        mapper bean exists in the context
     * @return the HTTP Mate tool client
     */
    @Bean
    @ConditionalOnMissingBean(MateToolClient.class)
    public MateToolClient mateToolClient(MateRestUtil restUtil, ObjectProvider<ObjectMapper> mapperProvider) {
        return new HttpMateToolClient(mateInnerGwAddress, restUtil, mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /**
     * Creates the callMateTool AgentTool bean.
     *
     * @param client the Mate tool client
     * @return the CallMateTool bean
     */
    @Bean
    public CallMateTool callMateTool(MateToolClient client) {
        return new CallMateTool(client);
    }

    /**
     * Creates the listMateTool AgentTool bean.
     *
     * @param client the Mate tool client
     * @return the ListMateTool bean
     */
    @Bean
    public ListMateTool listMateTool(MateToolClient client) {
        return new ListMateTool(client);
    }
}
