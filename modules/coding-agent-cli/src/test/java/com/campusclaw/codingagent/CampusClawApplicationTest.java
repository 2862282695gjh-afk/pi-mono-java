/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
        Logger logger = (Logger) LoggerFactory.getLogger(CampusClawApplication.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
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
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("errorCode=STARTUP_FAILED");
                assertThat(event.getThrowableProxy()).isNotNull();
            });
        } finally {
            logger.detachAppender(logs);
        }
    }
}
