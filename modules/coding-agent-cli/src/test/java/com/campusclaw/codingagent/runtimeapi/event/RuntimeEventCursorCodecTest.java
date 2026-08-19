/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;

/**
 * 不透明事件分页游标的加密、Session 绑定和过期测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeEventCursorCodecTest {
    private static final String SESSION_ID = "session_cursor_test";

    @Test
    void roundTripsEncryptedCursor() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));

        String cursor = codec.encode(SESSION_ID, 19L);

        assertThat(cursor).startsWith("page_").doesNotContain(SESSION_ID);
        assertThat(codec.decode(cursor, SESSION_ID)).isEqualTo(19L);
    }

    @Test
    void rejectsCursorBoundToAnotherSession() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));
        String cursor = codec.encode(SESSION_ID, 19L);

        assertInvalid(() -> codec.decode(cursor, "session_other"));
    }

    @Test
    void rejectsModifiedCursor() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));
        String cursor = codec.encode(SESSION_ID, 19L);
        char replacement = cursor.endsWith("A") ? 'B' : 'A';
        String modified = cursor.substring(0, cursor.length() - 1) + replacement;

        assertInvalid(() -> codec.decode(modified, SESSION_ID));
    }

    @Test
    void rejectsExpiredCursor() {
        RuntimeEventCursorCodec issuer = codecAt("2026-08-18T00:00:00Z", Duration.ofMinutes(1));
        String cursor = issuer.encode(SESSION_ID, 19L);
        RuntimeEventCursorCodec reader = codecAt("2026-08-18T00:02:00Z", Duration.ofMinutes(1));

        assertInvalid(() -> reader.decode(cursor, SESSION_ID));
    }

    @Test
    void rejectsNonPositiveCursorTtlAtStartup() {
        RuntimeEventProperties properties = new RuntimeEventProperties();
        properties.setCursorSecret("unit-test-cursor-secret");
        properties.setCursorTtl(Duration.ZERO);

        assertThatThrownBy(() -> new RuntimeEventCursorCodec(properties, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event cursor TTL must be positive");
    }

    private static RuntimeEventCursorCodec codecAt(String instant, Duration ttl) {
        RuntimeEventProperties properties = new RuntimeEventProperties();
        properties.setCursorSecret("unit-test-cursor-secret");
        properties.setCursorTtl(ttl);
        return new RuntimeEventCursorCodec(properties, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_EVENT_LIST_QUERY));
    }
}
