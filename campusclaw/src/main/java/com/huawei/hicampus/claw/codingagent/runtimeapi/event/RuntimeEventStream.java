/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用事件数和字节数双重上限隔离模型执行与单个 SSE 客户端。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeEventStream implements RuntimeEventOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeEventStream.class);

    private final Deque<BufferedEvent> events = new ArrayDeque<>();

    private final int maxEvents;

    private final long maxBytes;

    private final long heartbeatMillis;

    private final ToLongFunction<RuntimeSseEventVO> eventSizer;

    private long bufferedBytes;

    private boolean attached;

    private boolean completed;

    private boolean detached;

    private boolean closeActionRegistered;

    private Runnable closeAction;

    public RuntimeEventStream(
            int maxEvents, long maxBytes, Duration heartbeatInterval, ToLongFunction<RuntimeSseEventVO> eventSizer) {
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
        this.heartbeatMillis = heartbeatInterval.toMillis();
        this.eventSizer = eventSizer;
    }

    @Override
    public void emit(Supplier<RuntimeSseEventVO> event) {
        emit(event.get());
    }

    @Override
    public void emitBestEffort(Supplier<RuntimeSseEventVO> event) {
        emitBestEffort(event.get());
    }

    public boolean emit(RuntimeSseEventVO event) {
        Runnable action = null;
        boolean accepted = false;
        synchronized (this) {
            if (completed || detached) {
                return false;
            }
            long bytes = eventSizer.applyAsLong(event);
            evictBestEffortEvents(bytes);
            if (events.size() >= maxEvents || bytes > maxBytes - bufferedBytes) {
                action = detachInternal();
            } else {
                events.addLast(new BufferedEvent(event, bytes, false));
                bufferedBytes += bytes;
                accepted = true;
                notifyAll();
            }
        }
        runCloseAction(action);
        return accepted;
    }

    public synchronized boolean emitBestEffort(RuntimeSseEventVO event) {
        if (completed || detached) {
            return false;
        }
        long bytes;
        try {
            bytes = eventSizer.applyAsLong(event);
        } catch (RuntimeException error) {
            return false;
        }
        if (events.size() >= maxEvents || bytes > maxBytes - bufferedBytes) {
            return false;
        }
        events.addLast(new BufferedEvent(event, bytes, true));
        bufferedBytes += bytes;
        notifyAll();
        return true;
    }

    @Override
    public synchronized boolean canAcceptRequired(RuntimeSseEventVO event) {
        if (completed || detached) {
            return false;
        }
        long bytes = eventSizer.applyAsLong(event);
        int requiredEvents = 0;
        long requiredBytes = 0L;
        for (BufferedEvent buffered : events) {
            if (!buffered.bestEffort()) {
                requiredEvents++;
                requiredBytes += buffered.bytes();
            }
        }
        return requiredEvents < maxEvents && bytes <= maxBytes - requiredBytes;
    }

    @Override
    public boolean isWithinRequiredEventLimit(RuntimeSseEventVO event) {
        return maxEvents > 0 && eventSizer.applyAsLong(event) <= maxBytes;
    }

    @Override
    public void complete() {
        Runnable action;
        synchronized (this) {
            completed = true;
            action = takeCloseAction();
            notifyAll();
        }
        runCloseAction(action);
    }

    public void detach() {
        Runnable action;
        synchronized (this) {
            action = detachInternal();
        }
        runCloseAction(action);
    }

    /**
     * 注册流结束时执行的一次性清理动作。
     *
     * @param action 清理动作
     * @throws IllegalStateException 已注册过清理动作时抛出
     */
    public void onClose(Runnable action) {
        Objects.requireNonNull(action, "close action");
        boolean runImmediately;
        synchronized (this) {
            if (closeActionRegistered) {
                throw new IllegalStateException("runtime event stream close action is already registered");
            }
            closeActionRegistered = true;
            runImmediately = completed || detached;
            if (!runImmediately) {
                closeAction = action;
            }
        }
        runCloseAction(runImmediately ? action : null);
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
            boolean continueDraining;
            do {
                continueDraining = deliverNext(subscriber);
            } while (continueDraining);
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

    private Delivery awaitDelivery() {
        Runnable action = null;
        Delivery delivery;
        synchronized (this) {
            long deadline = System.currentTimeMillis() + heartbeatMillis;
            while (events.isEmpty() && !completed && !detached) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return Delivery.heartbeat();
                }
                action = waitForEvent(remaining);
            }
            if (!events.isEmpty()) {
                BufferedEvent buffered = events.removeFirst();
                bufferedBytes -= buffered.bytes();
                delivery = Delivery.event(buffered.event());
            } else {
                delivery = Delivery.terminal();
            }
        }
        runCloseAction(action);
        return delivery;
    }

    private Runnable waitForEvent(long millis) {
        try {
            wait(millis);
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return detachInternal();
        }
    }

    private Runnable detachInternal() {
        detached = true;
        events.clear();
        bufferedBytes = 0;
        notifyAll();
        return takeCloseAction();
    }

    private Runnable takeCloseAction() {
        Runnable action = closeAction;
        closeAction = null;
        return action;
    }

    private static void runCloseAction(Runnable action) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to clean up a closed Runtime event stream", exception);
        }
    }

    private void evictBestEffortEvents(long requiredBytes) {
        while (events.size() >= maxEvents || requiredBytes > maxBytes - bufferedBytes) {
            if (!removeOldestBestEffortEvent()) {
                return;
            }
        }
    }

    private boolean removeOldestBestEffortEvent() {
        Iterator<BufferedEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            BufferedEvent buffered = iterator.next();
            if (buffered.bestEffort()) {
                iterator.remove();
                bufferedBytes -= buffered.bytes();
                return true;
            }
        }
        return false;
    }

    private enum DeliveryKind {
        EVENT,
        HEARTBEAT,
        TERMINAL
    }

    private record BufferedEvent(RuntimeSseEventVO event, long bytes, boolean bestEffort) {}

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
