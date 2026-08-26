/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Runtime 失败日志和纯错误码异常测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeFailuresTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(RuntimeFailures.class);

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void logsRawFailureAndReturnsCauseFreeCodeException() {
        var original = new IllegalStateException("database unavailable");

        RuntimeApiException translated = RuntimeFailures.raise(
                "runtime.session.create",
                RuntimeErrorCode.SESSION_INITIALIZATION_FAILED,
                original,
                "sessionId",
                "session_test");

        assertThat(translated.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_INITIALIZATION_FAILED);
        assertThat(translated).hasMessage("SESSION_INITIALIZATION_FAILED").hasNoCause();
        assertThat(translated.getStackTrace()).isEmpty();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("operation=runtime.session.create")
                    .contains("errorCode=SESSION_INITIALIZATION_FAILED")
                    .contains("sessionId=session_test");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void logsClientFailureAtWarnWithoutInventingCause() {
        RuntimeApiException translated =
                RuntimeFailures.raise("runtime.events.validate", RuntimeErrorCode.INVALID_EVENT_REQUEST);

        assertThat(translated).hasNoCause();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
