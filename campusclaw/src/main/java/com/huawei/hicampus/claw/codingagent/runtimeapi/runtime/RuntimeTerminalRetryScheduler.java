/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.claw.agent.util.LoggingUncaughtExceptionHandler;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 在独立线程中重试已结束执行的权威终态提交，避免阻塞执行超时调度。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeTerminalRetryScheduler {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "runtime-terminal-retry");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
        return thread;
    });

    public void schedule(Runnable task, Duration delay) {
        scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 应用停止时取消尚未触发的终态重试。
     */
    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }
}
