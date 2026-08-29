/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.test.Log4j2TestAppender;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.Test;

/**
 * CampusClaw 仅服务模式入口测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class CampusClawApplicationTest {
    @Test
    void applicationCanBeConstructedWithoutCliDependencies() {
        assertThatNoException().isThrownBy(CampusClawApplication::new);
    }

    @Test
    void startupFailureLogsRawErrorAndThrowsOnlyCode() {
        Logger logger = (Logger) LogManager.getLogger(CampusClawApplication.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("startup-failure-logs");
        logs.start();
        logger.addAppender(logs);
        var original = new IllegalStateException("invalid startup configuration");
        try {
            assertThatThrownBy(() -> CampusClawApplication.launch(new String[0], ignored -> {
                        throw original;
                    }))
                    .isInstanceOf(CampusClawApplication.StartupException.class)
                    .hasMessage(CampusClawApplication.STARTUP_ERROR_CODE)
                    .hasNoCause();
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage()).contains("errorCode=STARTUP_FAILED");
                assertThat(event.getThrown()).isNotNull();
            });
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }
    }
}
