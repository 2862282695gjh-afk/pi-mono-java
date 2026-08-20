/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;

/**
 * 不透明事件分页游标的加密、Session 绑定和过期测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventCursorCodecTest {
    private static final String SESSION_ID = "session_cursor_test";

    @Test
    void roundTripsEncryptedCursor() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));

        String cursor = codec.encode(SESSION_ID, 19L, true);

        assertThat(cursor).startsWith("page_").doesNotContain(SESSION_ID);
        assertThat(codec.decode(cursor, SESSION_ID, true)).isEqualTo(19L);
    }

    @Test
    void rejectsCursorBoundToAnotherSession() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));
        String cursor = codec.encode(SESSION_ID, 19L, true);

        assertInvalid(() -> codec.decode(cursor, "session_other", true));
    }

    @Test
    void rejectsCursorWhenThinkingSettingHasChanged() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));
        String cursor = codec.encode(SESSION_ID, 19L, true);

        assertInvalid(() -> codec.decode(cursor, SESSION_ID, false));
    }

    @Test
    void rejectsModifiedCursor() {
        RuntimeEventCursorCodec codec = codecAt("2026-08-18T00:00:00Z", Duration.ofHours(1));
        String cursor = codec.encode(SESSION_ID, 19L, true);
        int position = cursor.length() / 2;
        char replacement = cursor.charAt(position) == 'A' ? 'B' : 'A';
        String modified = cursor.substring(0, position) + replacement + cursor.substring(position + 1);

        assertInvalid(() -> codec.decode(modified, SESSION_ID, true));
    }

    @Test
    void rejectsExpiredCursor() {
        RuntimeEventCursorCodec issuer = codecAt("2026-08-18T00:00:00Z", Duration.ofMinutes(1));
        String cursor = issuer.encode(SESSION_ID, 19L, true);
        RuntimeEventCursorCodec reader = codecAt("2026-08-18T00:02:00Z", Duration.ofMinutes(1));

        assertInvalid(() -> reader.decode(cursor, SESSION_ID, true));
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
