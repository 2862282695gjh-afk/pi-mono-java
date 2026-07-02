/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the in-process control plane into the host {@code SpringApplication}:
 * provides a default UTC {@link Clock} bean (the registry and sweepers inject it for
 * deterministic time-based logic) and turns on {@code @Scheduled} processing so the
 * sweeper can run at a fixed delay.
 *
 * <p>The control plane runs inside {@code CampusClawApplication} rather than as a
 * stand-alone Spring Boot process; this configuration is the single bridge between
 * the library-style {@code com.huawei.hicampus.mate.matecampusclaw.agent.controlplane} package tree and the
 * host application's bean factory.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Configuration
@EnableScheduling
public class ControlPlaneConfiguration {

    /**
     * System-default UTC clock used by the registry and the sweep scheduler. Tests can
     * override this bean by registering a fixed clock in their own {@code @Configuration}
     * with higher precedence ({@code @Primary} or {@link org.springframework.boot.test.context.TestConfiguration}).
     *
     * @return the default {@link Clock#systemUTC()}
     */
    @Bean
    public Clock controlPlaneClock() {
        return Clock.systemUTC();
    }
}
