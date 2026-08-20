/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;

/**
 * Runtime 显式消息源和无基础资源包启动行为测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/20]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeMessageSourceConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RuntimeMessageSourceConfiguration.class);

    @Test
    void contextStartsWithExplicitMessageSourceAndEnglishFallback() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ResourceBundleMessageSource.class);
            ResourceBundleMessageSource messageSource = context.getBean(ResourceBundleMessageSource.class);
            assertThat(messageSource.getMessage(RuntimeErrorCode.INTERNAL_ERROR.name(), null, Locale.FRANCE))
                    .isEqualTo("Internal service error.");
            assertThat(messageSource.getMessage(
                            RuntimeErrorCode.INTERNAL_ERROR.name(), null, Locale.SIMPLIFIED_CHINESE))
                    .isEqualTo("服务内部错误。");
        });
    }

    @Test
    void onlyExplicitLocaleBundlesExist() {
        assertThat(new ClassPathResource("messages.properties").exists()).isFalse();
        assertThat(new ClassPathResource("i18n/messages.properties").exists()).isFalse();
        assertThat(new ClassPathResource("i18n/messages_en_US.properties").exists())
                .isTrue();
        assertThat(new ClassPathResource("i18n/messages_zh_CN.properties").exists())
                .isTrue();
    }
}
