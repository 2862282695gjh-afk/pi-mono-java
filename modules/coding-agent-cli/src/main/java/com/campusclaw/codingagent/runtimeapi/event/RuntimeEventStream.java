/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.function.ToLongFunction;

import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

/**
 * 使用事件数和字节数双重上限隔离模型执行与单个 SSE 客户端。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class RuntimeEventStream {
    private final Deque<BufferedEvent> events = new ArrayDeque<>();

    private final int maxEvents;

    private final long maxBytes;

    private final long heartbeatMillis;

    private final ToLongFunction<RuntimeSseEventVO> eventSizer;

    private long bufferedBytes;

    private boolean attached;

    private boolean completed;

    private boolean detached;

    public RuntimeEventStream(
            int maxEvents, long maxBytes, Duration heartbeatInterval, ToLongFunction<RuntimeSseEventVO> eventSizer) {
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
        this.heartbeatMillis = heartbeatInterval.toMillis();
        this.eventSizer = eventSizer;
    }

    public synchronized boolean emit(RuntimeSseEventVO event) {
        if (completed || detached) {
            return false;
        }
        long bytes = eventSizer.applyAsLong(event);
        if (events.size() >= maxEvents || bytes > maxBytes - bufferedBytes) {
            detachInternal();
            return false;
        }
        events.addLast(new BufferedEvent(event, bytes));
        bufferedBytes += bytes;
        notifyAll();
        return true;
    }

    public synchronized void complete() {
        completed = true;
        notifyAll();
    }

    public synchronized void detach() {
        detachInternal();
    }

    public void attach(Executor executor, RuntimeEventSubscriber subscriber) {
        synchronized (this) {
            if (attached) {
                throw new IllegalStateException("runtime event stream already has a subscriber");
            }
            attached = true;
        }
        executor.execute(() -> drain(subscriber));
    }

    private void drain(RuntimeEventSubscriber subscriber) {
        try {
            while (deliverNext(subscriber)) {
                // 持续消费，直到流完成或客户端断开。
            }
            subscriber.onComplete();
        } catch (RuntimeException error) {
            detach();
            subscriber.onError(error);
        }
    }

    private boolean deliverNext(RuntimeEventSubscriber subscriber) {
        Delivery delivery = awaitDelivery();
        if (delivery.kind() == DeliveryKind.EVENT) {
            subscriber.onEvent(delivery.event());
            return true;
        }
        if (delivery.kind() == DeliveryKind.HEARTBEAT) {
            subscriber.onHeartbeat();
            return true;
        }
        return false;
    }

    private synchronized Delivery awaitDelivery() {
        long deadline = System.currentTimeMillis() + heartbeatMillis;
        while (events.isEmpty() && !completed && !detached) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return Delivery.heartbeat();
            }
            waitForEvent(remaining);
        }
        if (!events.isEmpty()) {
            BufferedEvent buffered = events.removeFirst();
            bufferedBytes -= buffered.bytes();
            return Delivery.event(buffered.event());
        }
        return Delivery.terminal();
    }

    private void waitForEvent(long millis) {
        try {
            wait(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            detachInternal();
        }
    }

    private void detachInternal() {
        detached = true;
        events.clear();
        bufferedBytes = 0;
        notifyAll();
    }

    private enum DeliveryKind {
        EVENT,
        HEARTBEAT,
        TERMINAL
    }

    private record BufferedEvent(RuntimeSseEventVO event, long bytes) {}

    private record Delivery(DeliveryKind kind, RuntimeSseEventVO event) {
        private static Delivery event(RuntimeSseEventVO event) {
            return new Delivery(DeliveryKind.EVENT, event);
        }

        private static Delivery heartbeat() {
            return new Delivery(DeliveryKind.HEARTBEAT, null);
        }

        private static Delivery terminal() {
            return new Delivery(DeliveryKind.TERMINAL, null);
        }
    }
}
