/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 管理 Runtime 执行硬超时的单线程调度器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeExecutionTimeoutScheduler {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "runtime-execution-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 应用停止时取消尚未触发的执行超时任务。
     */
    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }
}
