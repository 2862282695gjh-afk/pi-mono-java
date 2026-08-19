/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 加载真实 {@code application.yml}，验证 Mate 网关占位符不会循环引用且支持外部覆盖。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class ApplicationYmlLoadTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void mateGatewayPlaceholderResolvesWithoutCircularReference() {
        runner.run(context -> {
            String resolved = context.getEnvironment().getProperty("mate.innerGWSerive");
            assertThat(resolved).isEqualTo("");
        });
    }

    @Test
    void mateGatewayPlaceholderPicksUpEnvironmentVariable() {
        runner.withSystemProperties("MATE_INNERGWSERIVE=http://mate-gateway:8080")
                .run(context -> assertThat(context.getEnvironment().getProperty("mate.innerGWSerive"))
                        .isEqualTo("http://mate-gateway:8080"));
    }
}
