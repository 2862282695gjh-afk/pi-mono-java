/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.config;

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
}
