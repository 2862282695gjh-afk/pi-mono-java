/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.junit.jupiter.api.Test;

/**
 * 有界 Runtime SSE 缓冲、断开和心跳语义测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventStreamTest {
    @Test
    void bestEffortOverflowDropsOnlyProgressAndPreservesFinalEvent() throws Exception {
        RuntimeEventStream stream = new RuntimeEventStream(1, 100, Duration.ofSeconds(1), ignored -> 10L);
        RuntimeSseEventVO progress = new RuntimeSseEventVO(null, "tool.execution.delta", Map.of());
        RuntimeSseEventVO completed = new RuntimeSseEventVO("1", "tool.result", Map.of());

        assertThat(stream.emitBestEffort(progress)).isTrue();
        assertThat(stream.emitBestEffort(progress)).isFalse();
        assertThat(stream.emit(completed)).isTrue();
        stream.complete();
        LatchSubscriber subscriber = new LatchSubscriber();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            stream.attach(executor, subscriber);
            assertThat(subscriber.firstEvent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(subscriber.completed.await(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(subscriber.events).containsExactly(completed);
    }

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
    void shouldPreflightRequiredEventWithoutMutatingStream() {
        RuntimeEventStream stream = stream(1, 1);

        assertThat(stream.canAcceptRequired(event("fits"))).isTrue();
        assertThat(stream.canAcceptRequired(event("fits"))).isTrue();
        assertThat(stream.emit(event("fits"))).isTrue();
        assertThat(stream.canAcceptRequired(event("full"))).isFalse();
    }

    @Test
    void shouldPreflightAfterEvictingBestEffortEvents() {
        RuntimeEventStream stream = new RuntimeEventStream(1, 10, Duration.ofSeconds(15), ignored -> 10L);
        assertThat(stream.emitBestEffort(event("delta"))).isTrue();

        assertThat(stream.canAcceptRequired(event("complete"))).isTrue();
        assertThat(stream.emit(event("complete"))).isTrue();
    }

    @Test
    void shouldCheckSingleEventLimitIndependentlyFromClientState() {
        RuntimeEventStream stream = new RuntimeEventStream(1, 1, Duration.ofSeconds(15), event -> 1L);
        stream.detach();

        assertThat(stream.isWithinRequiredEventLimit(event("fits"))).isTrue();
        RuntimeEventStream oversized = new RuntimeEventStream(1, 1, Duration.ofSeconds(15), event -> 2L);
        assertThat(oversized.isWithinRequiredEventLimit(event("large"))).isFalse();
    }

    @Test
    void shouldRunCloseActionOnlyOnce() {
        RuntimeEventStream stream = stream(1, 10);
        var closed = new AtomicInteger();
        stream.onClose(closed::incrementAndGet);

        stream.complete();
        stream.complete();
        stream.detach();

        assertThat(closed).hasValue(1);
    }

    @Test
    void shouldRunLateCloseActionAndIgnoreItsFailure() {
        RuntimeEventStream stream = stream(1, 10);
        stream.detach();
        var closed = new AtomicInteger();

        stream.onClose(() -> {
            closed.incrementAndGet();
            throw new IllegalStateException("cleanup failure");
        });

        assertThat(closed).hasValue(1);
        assertThat(stream.emit(event("after-close"))).isFalse();
    }

    @Test
    void shouldRunCloseActionWhenRequiredEventOverflows() {
        RuntimeEventStream stream = new RuntimeEventStream(1, 1, Duration.ofSeconds(15), event -> 2L);
        var closed = new AtomicInteger();
        stream.onClose(closed::incrementAndGet);

        assertThat(stream.emit(event("too-large"))).isFalse();

        assertThat(closed).hasValue(1);
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

    private static final class LatchSubscriber implements RuntimeEventSubscriber {
        private final List<RuntimeSseEventVO> events = new ArrayList<>();

        private final CountDownLatch firstEvent = new CountDownLatch(1);

        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onEvent(RuntimeSseEventVO event) {
            events.add(event);
            firstEvent.countDown();
        }

        @Override
        public void onHeartbeat() {}

        @Override
        public void onComplete() {
            completed.countDown();
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
