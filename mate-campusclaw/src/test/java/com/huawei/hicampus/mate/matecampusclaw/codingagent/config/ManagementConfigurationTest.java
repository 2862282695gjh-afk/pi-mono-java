/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证公司父项目引入 Actuator 时的管理面关闭配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/29]
 * @since [br_eCampusCore 26.0.0]
 */
class ManagementConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void disablesManagementServerAndManagementWebAutoConfigurations() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("management.server.port"))
                    .isEqualTo("-1");
            assertThat(context.getEnvironment().getProperty("management.endpoints.enabled-by-default"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.autoconfigure.exclude"))
                    .isNotNull()
                    .satisfies(exclusions -> assertThat(exclusions.split(","))
                            .contains(
                                    "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration",
                                    "org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration",
                                    "org.springframework.boot.actuate.autoconfigure.web.servlet.ServletManagementContextAutoConfiguration"));
        });
    }
}
