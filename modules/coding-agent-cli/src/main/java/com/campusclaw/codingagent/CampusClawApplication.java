/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent;

import com.campusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.campusclaw.codingagent.config.ToolExecutionProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * CampusClaw Spring Boot 服务入口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/05/06]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@SpringBootApplication(scanBasePackages = "com.campusclaw")
@EnableConfigurationProperties({ToolExecutionProperties.class, ControlPlaneProperties.class})
public class CampusClawApplication {

    public static void main(String[] args) {
        if (CampusClawCliLauncher.isCliInvocation(args)) {
            CampusClawCliLauncher.launch(args);
            return;
        }
        SpringApplication.run(CampusClawApplication.class, args);
    }
}
