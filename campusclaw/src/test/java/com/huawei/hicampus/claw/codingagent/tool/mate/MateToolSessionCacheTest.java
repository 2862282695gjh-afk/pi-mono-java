/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link MateToolSessionCache} 的 single-flight 刷新失败传播测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolSessionCacheTest {

    @Test
    void concurrentMissesShouldShareTheSameFailedRefresh() throws Exception {
        var cache = new MateToolSessionCache();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("discovery failed");
        var secondThread = new AtomicReference<Thread>();
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    () -> cache.resolveOrRefresh("Query", () -> failRefresh(started, release, calls, failure)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondStarted.countDown();
                return cache.resolveOrRefresh("Query", () -> failRefresh(started, release, calls, failure));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitWaiting(secondThread.get())).isTrue();
            release.countDown();

            assertThat(executionCause(first)).isSameAs(failure);
            assertThat(executionCause(second)).isSameAs(failure);
        }
        assertThat(calls).hasValue(1);
    }

    private static boolean awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static Map<MateToolSource, java.util.List<com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta>>
            failRefresh(CountDownLatch started, CountDownLatch release, AtomicInteger calls, RuntimeException failure) {
        calls.incrementAndGet();
        started.countDown();
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        throw failure;
    }

    private static Throwable executionCause(java.util.concurrent.Future<String> future) throws InterruptedException {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("refresh should fail");
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new AssertionError("refresh timed out", exception);
        }
    }
}
