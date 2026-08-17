/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEventStream;

/**
 * 单个 Session 当前唯一活动执行的进程内句柄。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class RuntimeActiveExecution {
    private final RuntimeEventStream eventStream;

    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private boolean acceptingControls = true;

    private boolean abortRequested;

    public RuntimeActiveExecution(RuntimeEventStream eventStream) {
        this.eventStream = eventStream;
    }

    public RuntimeEventStream eventStream() {
        return eventStream;
    }

    public boolean acceptingControls() {
        return acceptingControls;
    }

    public void closeControls() {
        acceptingControls = false;
    }

    public void requestAbort() {
        abortRequested = true;
        acceptingControls = false;
    }

    public boolean abortRequested() {
        return abortRequested;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public void complete(Throwable failure) {
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
    }
}
