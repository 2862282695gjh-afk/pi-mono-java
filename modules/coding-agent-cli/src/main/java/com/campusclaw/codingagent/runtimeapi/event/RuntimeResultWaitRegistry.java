/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ResultWaitTargetDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按真实响应数量限制并按固定目标去重数据库结果等待。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeResultWaitRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeResultWaitRegistry.class);

    private static final Comparator<WaitKey> KEY_ORDER = Comparator.comparing(
                    (WaitKey key) -> key.target().sessionId())
            .thenComparing(key -> key.target().executionId())
            .thenComparing(key -> key.target().rootEventId())
            .thenComparing(key -> key.target().segmentId())
            .thenComparing(key -> key.mode().name());

    private final Map<Long, Waiter> waiters = new HashMap<>();

    private final Map<WaitKey, WaitGroup> groups = new HashMap<>();

    private final int maxResponses;

    private final long waitNanos;

    private long nextWaiterId;

    private long nextClaimOrder;

    private long nextClaimId;

    public RuntimeResultWaitRegistry(RuntimeEventProperties properties) {
        this.maxResponses = properties.getResultWaitMaxResponses();
        this.waitNanos = properties.getResultWaitTimeout().toNanos();
    }

    /**
     * 在用户事件接受前预留一个真实响应名额。
     *
     * @param mode 等待范围
     * @param delivery 独立响应投递入口
     * @param timeoutAction 超时后断开本条观察响应的动作
     * @return 容量充足时返回预留句柄
     */
    public synchronized Optional<Reservation> reserve(
            RuntimeResultWaitMode mode, RuntimeCommittedResultDelivery delivery, Runnable timeoutAction) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(timeoutAction, "timeout action");
        if (waiters.size() >= maxResponses) {
            return Optional.empty();
        }
        long id = ++nextWaiterId;
        waiters.put(id, new Waiter(id, mode, delivery, timeoutAction, deadlineFromNow()));
        return Optional.of(new Reservation(id));
    }

    /**
     * 领取一批去重后的固定目标；同组查询完成前不会再次领取。
     *
     * @param limit 最大目标数量
     * @return 按公平顺序领取的目标
     */
    public List<ResultWaitTargetDTO> claimTargets(int limit) {
        List<Runnable> expired;
        List<ResultWaitTargetDTO> claimed;
        synchronized (this) {
            expired = removeExpired(System.nanoTime());
            claimed = claimGroups(groups.values().stream()
                    .filter(group -> !group.inFlight)
                    .sorted(Comparator.comparingLong((WaitGroup group) -> group.lastClaimOrder)
                            .thenComparing(group -> group.key, KEY_ORDER))
                    .limit(Math.max(0, limit))
                    .toList());
        }
        runTimeoutActions(expired);
        return claimed;
    }

    /**
     * 领取一个刚提交目标的本机等待组，用于提交后快速补读。
     *
     * @param target 已提交事件所属固定目标
     * @return 该目标当前可领取的等待范围
     */
    public List<ResultWaitTargetDTO> claimTarget(ExecutionTargetDTO target) {
        requireTarget(target);
        List<Runnable> expired;
        List<ResultWaitTargetDTO> claimed;
        synchronized (this) {
            expired = removeExpired(System.nanoTime());
            claimed = claimGroups(groups.values().stream()
                    .filter(group -> !group.inFlight && group.key.target().equals(target))
                    .sorted(Comparator.comparing(group -> group.key, KEY_ORDER))
                    .toList());
        }
        runTimeoutActions(expired);
        return claimed;
    }

    /**
     * 将一次权威补读结果逐响应去重投递。
     *
     * @param claimed 已领取的固定等待目标
     * @param events 已提交完整事件
     * @param terminal 当前等待范围是否已经结束
     */
    public void deliver(ResultWaitTargetDTO claimed, List<CommittedEventDTO> events, boolean terminal) {
        List<DeliveryAttempt> attempts;
        synchronized (this) {
            WaitGroup group = claimedGroup(claimed);
            if (group == null) {
                return;
            }
            attempts = deliveryAttempts(group, events, terminal);
        }
        attempts.forEach(this::deliverOne);
        synchronized (this) {
            finishDelivery(claimed, attempts);
        }
    }

    /**
     * 查询失败时释放目标，保留全部响应供后续重试。
     *
     * @param claimed 已领取的固定等待目标
     */
    public synchronized void release(ResultWaitTargetDTO claimed) {
        WaitGroup group = claimedGroup(claimed);
        if (group != null) {
            group.inFlight = false;
        }
    }

    synchronized int registeredResponses() {
        return waiters.size();
    }

    void expireTimedOut(long nowNanos) {
        List<Runnable> expired;
        synchronized (this) {
            expired = removeExpired(nowNanos);
        }
        runTimeoutActions(expired);
    }

    private synchronized boolean bind(long id, ExecutionTargetDTO target, long afterSeq) {
        requireTarget(target);
        if (afterSeq < 0) {
            throw new IllegalArgumentException("result wait cursor is invalid");
        }
        Waiter waiter = waiters.get(id);
        if (waiter == null || waiter.key != null) {
            return false;
        }
        waiter.afterSeq = afterSeq;
        waiter.key = new WaitKey(target, waiter.mode);
        groups.computeIfAbsent(waiter.key, WaitGroup::new).waiters.add(waiter);
        return true;
    }

    private synchronized void close(long id) {
        removeWaiter(waiters.get(id));
    }

    private List<ResultWaitTargetDTO> claimGroups(List<WaitGroup> candidates) {
        List<ResultWaitTargetDTO> claimed = new ArrayList<>(candidates.size());
        for (WaitGroup group : candidates) {
            group.inFlight = true;
            group.lastClaimOrder = ++nextClaimOrder;
            group.claimId = ++nextClaimId;
            long afterSeq = group.waiters.stream()
                    .mapToLong(waiter -> waiter.afterSeq)
                    .min()
                    .orElse(0L);
            claimed.add(new ResultWaitTargetDTO(group.key.target(), group.key.mode(), afterSeq, group.claimId));
        }
        return List.copyOf(claimed);
    }

    private WaitGroup claimedGroup(ResultWaitTargetDTO claimed) {
        WaitGroup group = groups.get(new WaitKey(claimed.target(), claimed.mode()));
        return group != null && group.inFlight && group.claimId == claimed.claimId() ? group : null;
    }

    private List<DeliveryAttempt> deliveryAttempts(WaitGroup group, List<CommittedEventDTO> events, boolean terminal) {
        List<CommittedEventDTO> committed = List.copyOf(events);
        List<DeliveryAttempt> attempts = new ArrayList<>(group.waiters.size());
        for (Waiter waiter : List.copyOf(group.waiters)) {
            List<CommittedEventDTO> pending = committed.stream()
                    .filter(event -> event.getEventSeq() > waiter.afterSeq)
                    .toList();
            if (!pending.isEmpty() || terminal) {
                attempts.add(new DeliveryAttempt(waiter, pending, terminal));
            }
        }
        return attempts;
    }

    private void deliverOne(DeliveryAttempt attempt) {
        try {
            attempt.accepted = attempt.waiter.delivery.deliver(attempt.events, attempt.terminal);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to enqueue committed Runtime result", exception);
        }
    }

    private void finishDelivery(ResultWaitTargetDTO claimed, List<DeliveryAttempt> attempts) {
        WaitGroup group = claimedGroup(claimed);
        if (group == null) {
            return;
        }
        for (DeliveryAttempt attempt : attempts) {
            Waiter waiter = waiters.get(attempt.waiter.id);
            if (waiter == null || waiter != attempt.waiter) {
                continue;
            }
            if (!attempt.accepted || attempt.terminal) {
                removeWaiter(waiter);
            } else if (!attempt.events.isEmpty()) {
                waiter.afterSeq = attempt.events.getLast().getEventSeq();
            }
        }
        WaitGroup current = groups.get(group.key);
        if (current != null) {
            current.inFlight = false;
        }
    }

    private List<Runnable> removeExpired(long nowNanos) {
        List<Runnable> actions = new ArrayList<>();
        for (Waiter waiter : List.copyOf(waiters.values())) {
            if (nowNanos - waiter.deadlineNanos >= 0) {
                actions.add(waiter.timeoutAction);
                removeWaiter(waiter);
            }
        }
        return actions;
    }

    private void removeWaiter(Waiter waiter) {
        if (waiter == null || waiters.remove(waiter.id) == null || waiter.key == null) {
            return;
        }
        WaitGroup group = groups.get(waiter.key);
        if (group != null) {
            group.waiters.remove(waiter);
            if (group.waiters.isEmpty()) {
                groups.remove(waiter.key);
            }
        }
    }

    private static void runTimeoutActions(List<Runnable> actions) {
        actions.forEach(action -> {
            try {
                action.run();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close a timed-out Runtime result response", exception);
            }
        });
    }

    private long deadlineFromNow() {
        return System.nanoTime() + waitNanos;
    }

    private static void requireTarget(ExecutionTargetDTO target) {
        Objects.requireNonNull(target, "target");
        if (isBlank(target.sessionId())
                || isBlank(target.executionId())
                || isBlank(target.rootEventId())
                || isBlank(target.segmentId())) {
            throw new IllegalArgumentException("execution target is incomplete");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 接受前容量预留以及提交后固定目标绑定句柄。
     */
    public final class Reservation implements AutoCloseable {
        private final long id;

        private Reservation(long id) {
            this.id = id;
        }

        /**
         * 在完整回执已经排队后绑定数据库固定目标和内部读取游标。
         *
         * @param target 已接受的固定执行目标
         * @param afterSeq 本条响应已经排队的回执序号
         * @return 预留是否仍有效并完成绑定
         */
        public boolean bind(ExecutionTargetDTO target, long afterSeq) {
            return RuntimeResultWaitRegistry.this.bind(id, target, afterSeq);
        }

        @Override
        public void close() {
            RuntimeResultWaitRegistry.this.close(id);
        }
    }

    private record WaitKey(ExecutionTargetDTO target, RuntimeResultWaitMode mode) {}

    private static final class WaitGroup {
        private final WaitKey key;

        private final List<Waiter> waiters = new ArrayList<>();

        private boolean inFlight;

        private long lastClaimOrder;

        private long claimId;

        private WaitGroup(WaitKey key) {
            this.key = key;
        }
    }

    private static final class Waiter {
        private final long id;

        private final RuntimeResultWaitMode mode;

        private final RuntimeCommittedResultDelivery delivery;

        private final Runnable timeoutAction;

        private final long deadlineNanos;

        private WaitKey key;

        private long afterSeq;

        private Waiter(
                long id,
                RuntimeResultWaitMode mode,
                RuntimeCommittedResultDelivery delivery,
                Runnable timeoutAction,
                long deadlineNanos) {
            this.id = id;
            this.mode = mode;
            this.delivery = delivery;
            this.timeoutAction = timeoutAction;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static final class DeliveryAttempt {
        private final Waiter waiter;

        private final List<CommittedEventDTO> events;

        private final boolean terminal;

        private boolean accepted;

        private DeliveryAttempt(Waiter waiter, List<CommittedEventDTO> events, boolean terminal) {
            this.waiter = waiter;
            this.events = events;
            this.terminal = terminal;
        }
    }
}
