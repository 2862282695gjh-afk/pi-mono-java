/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Runtime 错误消息资源的显式加载配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/20]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeMessageSourceConfiguration {
    @Bean
    public ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename(RuntimeApiConstants.MESSAGE_BUNDLE_BASENAME);
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setDefaultLocale(Locale.US);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
