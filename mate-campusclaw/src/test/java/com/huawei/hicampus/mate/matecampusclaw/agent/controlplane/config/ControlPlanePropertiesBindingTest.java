/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies that {@code controlplane.heartbeat.*} keys in the active property source
 * actually flow into {@link ControlPlaneProperties}.
 *
 * <p>Uses {@link ApplicationContextRunner} so the test owns its minimal Spring context —
 * agent-core is a library module without an {@code @SpringBootApplication} entry point,
 * and dragging in coding-agent-cli's main class would couple the unit test to the CLI.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class ControlPlanePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @Test
    void overriddenHeartbeatValuesAreBoundFromPropertySource() {
        runner.withPropertyValues(
                        "controlplane.heartbeat.ttl=PT7S",
                        "controlplane.heartbeat.sweep-interval=PT2S",
                        "controlplane.heartbeat.grace-after-stale=PT45S")
                .run(context -> {
                    ControlPlaneProperties props = context.getBean(ControlPlaneProperties.class);
                    assertThat(props.heartbeat().ttl().getSeconds()).isEqualTo(7L);
                    assertThat(props.heartbeat().sweepInterval().getSeconds()).isEqualTo(2L);
                    assertThat(props.heartbeat().graceAfterStale().getSeconds()).isEqualTo(45L);
                });
    }

    @Test
    void missingHeartbeatBlockFallsBackToBuiltInDefaults() {
        runner.run(context -> {
            ControlPlaneProperties props = context.getBean(ControlPlaneProperties.class);
            assertThat(props.heartbeat().ttl().getSeconds()).isEqualTo(30L);
            assertThat(props.heartbeat().sweepInterval().getSeconds()).isEqualTo(10L);
            assertThat(props.heartbeat().graceAfterStale().toMinutes()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Configuration
    @EnableConfigurationProperties(ControlPlaneProperties.class)
    static class EnableProps {}
}
