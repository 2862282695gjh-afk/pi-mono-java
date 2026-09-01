/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent;

import com.huawei.hicampus.claw.agent.controlplane.config.ControlPlaneProperties;
import com.huawei.hicampus.claw.codingagent.config.CampusMateClientProperties;
import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeProperties;
import com.huawei.hicampus.claw.codingagent.session.compaction.CompactionProperties;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * CampusClaw Spring Boot 服务入口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@SpringBootApplication(scanBasePackages = "com.huawei.hicampus.claw")
@EnableConfigurationProperties({
    ControlPlaneProperties.class,
    CampusMateClientProperties.class,
    AgentRuntimeProperties.class,
    BuiltInToolProperties.class,
    CompactionProperties.class
})
public class CampusClawApplication {
    static final String STARTUP_ERROR_CODE = "STARTUP_FAILED";

    private static final Logger LOGGER = LoggerFactory.getLogger(CampusClawApplication.class);

    public static void main(String[] args) {
        launch(args, values -> SpringApplication.run(CampusClawApplication.class, values));
    }

    static void launch(String[] args, ApplicationLauncher launcher) {
        try {
            launcher.run(args);
        } catch (RuntimeException error) {
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "application.startup")
                    .addKeyValue("errorCode", STARTUP_ERROR_CODE)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}, context={}",
                            "application.startup",
                            STARTUP_ERROR_CODE,
                            java.util.Map.of());
            throw new StartupException();
        }
    }

    @FunctionalInterface
    interface ApplicationLauncher {
        void run(String[] args);
    }

    static final class StartupException extends RuntimeException {
        private StartupException() {
            super(STARTUP_ERROR_CODE, null, false, false);
        }
    }
}
