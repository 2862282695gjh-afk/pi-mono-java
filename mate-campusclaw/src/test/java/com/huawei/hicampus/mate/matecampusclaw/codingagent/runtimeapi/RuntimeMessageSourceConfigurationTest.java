/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.ResourceBundleMessageSource;

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
            assertThat(messageSource.getBasenameSet()).containsExactly(RuntimeApiConstants.MESSAGE_BUNDLE_BASENAME);
            assertThat(messageSource.getMessage(RuntimeErrorCode.INTERNAL_ERROR.name(), null, Locale.FRANCE))
                    .isEqualTo("Internal service error.");
            assertThat(messageSource.getMessage(
                            RuntimeErrorCode.INTERNAL_ERROR.name(), null, Locale.SIMPLIFIED_CHINESE))
                    .isEqualTo("服务内部错误。");
        });
    }

    @Test
    void onlyExplicitLocaleBundlesExistInModuleOutput() throws URISyntaxException {
        Path moduleOutput = Path.of(RuntimeMessageSourceConfiguration.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());

        assertThat(moduleOutput.resolve("messages.properties")).doesNotExist();
        assertThat(moduleOutput.resolve("i18n/messages.properties")).doesNotExist();
        assertThat(moduleOutput.resolve("i18n/campusclaw_messages.properties")).doesNotExist();
        assertThat(moduleOutput.resolve("i18n/campusclaw_messages_en_US.properties"))
                .isRegularFile();
        assertThat(moduleOutput.resolve("i18n/campusclaw_messages_zh_CN.properties"))
                .isRegularFile();
    }
}
