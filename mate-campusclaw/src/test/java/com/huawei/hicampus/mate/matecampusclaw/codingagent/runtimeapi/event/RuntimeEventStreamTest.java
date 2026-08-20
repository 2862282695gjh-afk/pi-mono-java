/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.junit.jupiter.api.Test;

/**
 * 有界 Runtime SSE 缓冲、断开和心跳语义测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventStreamTest {
    @Test
    void drainsAcceptedEventsInOrderAfterCompletion() {
        RuntimeEventStream stream = stream(2, 10);
        assertThat(stream.emit(event("first"))).isTrue();
        assertThat(stream.emit(event("second"))).isTrue();
        stream.complete();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        stream.attach(Runnable::run, subscriber);

        assertThat(subscriber.events).extracting(RuntimeSseEventVO::getEvent).containsExactly("first", "second");
        assertThat(subscriber.completed).isTrue();
    }

    @Test
    void detachesOnlyClientWhenBufferLimitIsExceeded() {
        RuntimeEventStream stream = stream(1, 10);
        assertThat(stream.emit(event("first"))).isTrue();
        assertThat(stream.emit(event("overflow"))).isFalse();
        CollectingSubscriber subscriber = new CollectingSubscriber();

        stream.attach(Runnable::run, subscriber);

        assertThat(subscriber.events).isEmpty();
        assertThat(subscriber.completed).isTrue();
    }

    @Test
    void sendsHeartbeatWhileExecutionHasNoEvents() throws Exception {
        RuntimeEventStream stream = new RuntimeEventStream(2, 10, Duration.ofMillis(5), event -> 1L);
        CountDownLatch heartbeat = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            stream.attach(executor, new HeartbeatSubscriber(stream, heartbeat));
            assertThat(heartbeat.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RuntimeEventStream stream(int maxEvents, long maxBytes) {
        return new RuntimeEventStream(maxEvents, maxBytes, Duration.ofSeconds(15), event -> 1L);
    }

    private static RuntimeSseEventVO event(String name) {
        return new RuntimeSseEventVO(null, name, Map.of());
    }

    private static final class CollectingSubscriber implements RuntimeEventSubscriber {
        private final List<RuntimeSseEventVO> events = new ArrayList<>();

        private boolean completed;

        @Override
        public void onEvent(RuntimeSseEventVO event) {
            events.add(event);
        }

        @Override
        public void onHeartbeat() {
            throw new AssertionError("completed stream must not emit heartbeat");
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError(error);
        }
    }

    private record HeartbeatSubscriber(RuntimeEventStream stream, CountDownLatch heartbeat)
            implements RuntimeEventSubscriber {
        @Override
        public void onEvent(RuntimeSseEventVO event) {
            throw new AssertionError("no event expected");
        }

        @Override
        public void onHeartbeat() {
            heartbeat.countDown();
            stream.detach();
        }

        @Override
        public void onComplete() {
            // detach 会自然结束 drain。
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError(error);
        }
    }
}
