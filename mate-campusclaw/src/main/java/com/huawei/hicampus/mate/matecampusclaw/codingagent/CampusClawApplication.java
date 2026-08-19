/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * CampusClaw Spring Boot 服务入口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@SpringBootApplication(scanBasePackages = "com.huawei.hicampus.mate.matecampusclaw")
@EnableConfigurationProperties({
    ControlPlaneProperties.class,
    AgentRuntimeProperties.class
})
public class CampusClawApplication {

    public static void main(String[] args) {
        if (CampusClawCliLauncher.isCliInvocation(args)) {
            CampusClawCliLauncher.launch(args);
            return;
        }
        SpringApplication.run(CampusClawApplication.class, args);
    }
}
