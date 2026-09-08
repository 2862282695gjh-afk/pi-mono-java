/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runtime v2 data-only SSE 写出约束测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSseEmitterSubscriberTest {
    @Test
    void shouldWriteDataOnlyFrameAndPingWhenV2ModeEnabled() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        RuntimeSseEmitterSubscriber subscriber = new RuntimeSseEmitterSubscriber(emitter, true);
        RuntimeSseEventVO event = RuntimeSseEventVO.dataOnly("user.message", Map.of("eventId", "event_1"));

        subscriber.onEvent(event);
        subscriber.onHeartbeat();

        assertThat(emitter.frames).hasSize(2);
        assertThat(emitter.frames.getFirst()).containsExactly("data:", event.getData(), "\n\n");
        assertThat(event.getData())
                .hasSize(2)
                .containsEntry("type", "user.message")
                .containsEntry("eventId", "event_1");
        assertThat(emitter.frames.getLast()).containsExactly(": ping\n\n");
    }

    @Test
    void shouldRejectLegacyFrameWhenV2ModeEnabled() {
        RuntimeSseEmitterSubscriber subscriber = new RuntimeSseEmitterSubscriber(new CapturingSseEmitter(), true);
        RuntimeSseEventVO legacy = new RuntimeSseEventVO("17", "user.message", Map.of("eventId", "event_1"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> subscriber.onEvent(legacy));

        assertThat(error).hasMessageContaining("data-only");
    }

    private static final class CapturingSseEmitter extends SseEmitter {
        private final List<List<Object>> frames = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            frames.add(builder.build().stream().map(item -> item.getData()).toList());
        }
    }
}
