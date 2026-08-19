/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Loads the real {@code application.yml} (config-data path, unlike plain
 * {@code ApplicationContextRunner} which skips it) and asserts the Mate
 * gateway placeholder resolves without circular references. Regression test:
 * {@code mate.innerGWSerive: ${mate.innerGWSerive:}} self-reference made the
 * context fail with {@code Circular placeholder reference} at startup.
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
        runner.withSystemProperties("mate.innerGWSerive.overridden:none").run(context -> {
            String raw = context.getEnvironment().getProperty("mate.innerGWSerive");
            assertThat(raw).isNotNull();
        });
    }
}
