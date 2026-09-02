/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/**
 * 验证公司镜像手工维护的 CampusMate 配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/01]
 * @since [br_eCampusCore 26.0.0]
 */
class CampusMateConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getEnvironment()
                                .getPropertySources()
                                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                        new ConfigDataApplicationContextInitializer().initialize(context);
                    });

    @Test
    void usesDefaultCampusMateBaseUrl() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("campusmate.base-url"))
                .isEqualTo("https://localhost:8591"));
    }

    @Test
    void allowsCampusMateBaseUrlOverride() {
        runner.withSystemProperties("CAMPUSMATE_BASE_URL=https://campusmate.example.com:9443")
                .run(context -> assertThat(context.getEnvironment().getProperty("campusmate.base-url"))
                        .isEqualTo("https://campusmate.example.com:9443"));
    }
}
