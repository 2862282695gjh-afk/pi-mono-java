/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent;

import com.campusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.campusclaw.codingagent.runtime.AgentRuntimeProperties;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * CampusClaw Spring Boot 服务入口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@SpringBootApplication(scanBasePackages = "com.campusclaw")
@EnableConfigurationProperties({ControlPlaneProperties.class, AgentRuntimeProperties.class, BuiltInToolProperties.class
})
public class CampusClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusClawApplication.class, args);
    }
}
