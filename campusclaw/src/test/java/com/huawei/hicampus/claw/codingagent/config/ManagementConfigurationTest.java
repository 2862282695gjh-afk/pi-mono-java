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
 * 验证公司镜像手工维护的应用配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/01]
 * @since [br_eCampusCore 26.0.0]
 */
class ManagementConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getEnvironment()
                                .getPropertySources()
                                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                        new ConfigDataApplicationContextInitializer().initialize(context);
                    });

    @Test
    void usesPropertiesInsteadOfRedundantManagementExclusions() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("management.server.port"))
                    .isEqualTo("-1");
            assertThat(context.getEnvironment().getProperty("management.endpoints.enabled-by-default"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.autoconfigure.exclude"))
                    .isNotNull()
                    .satisfies(exclusions -> {
                        assertThat(exclusions.split(","))
                                .containsExactly(
                                        "org.springframework.boot.actuate.autoconfigure.amqp.RabbitHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.data.mongo.MongoHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticsearchRestHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.info.InfoContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.jms.JmsHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.mail.MailHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.system.DiskSpaceHealthContributorAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesAutoConfiguration",
                                        "org.springframework.boot.actuate.autoconfigure.web.servlet.ServletManagementContextAutoConfiguration");
                    });
        });
    }

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
