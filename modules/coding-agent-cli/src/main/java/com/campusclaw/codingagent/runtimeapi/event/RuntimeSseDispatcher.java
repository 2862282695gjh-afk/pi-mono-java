/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 为每个 SSE 客户端提供独立虚拟线程的写出调度器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeSseDispatcher implements Executor {
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("runtime-sse-", 0).factory());

    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    /**
     * 应用停止时拒绝新任务，并让已写出任务自然结束。
     */
    @PreDestroy
    public void close() {
        executor.shutdown();
    }
}
