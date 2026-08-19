/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
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

    private final Map<Message, Long> queuedControls = new IdentityHashMap<>();

    private long queuedControlBytes;

    private boolean acceptingControls = true;

    private boolean abortRequested;

    private boolean timedOut;

    private Future<?> timeoutTask;

    public RuntimeActiveExecution(RuntimeEventStream eventStream) {
        this.eventStream = eventStream;
    }

    public RuntimeEventStream eventStream() {
        return eventStream;
    }

    public synchronized boolean acceptingControls() {
        return acceptingControls;
    }

    public synchronized void closeControls() {
        acceptingControls = false;
    }

    public synchronized void requestAbort() {
        abortRequested = true;
        acceptingControls = false;
        clearQueuedControls();
    }

    public synchronized void requestTimeout() {
        timedOut = true;
        acceptingControls = false;
        clearQueuedControls();
    }

    public synchronized boolean abortRequested() {
        return abortRequested;
    }

    public synchronized boolean timedOut() {
        return timedOut;
    }

    public synchronized void setTimeoutTask(Future<?> task) {
        if (completion.isDone()) {
            task.cancel(false);
        } else {
            timeoutTask = task;
        }
    }

    public synchronized boolean queueControl(Message message, long bytes, int maxMessages, long maxBytes) {
        if (!acceptingControls || queuedControls.size() >= maxMessages || queuedControlBytes + bytes > maxBytes) {
            return false;
        }
        queuedControls.put(message, bytes);
        queuedControlBytes += bytes;
        return true;
    }

    public synchronized void controlDelivered(Message message) {
        Long bytes = queuedControls.remove(message);
        if (bytes != null) {
            queuedControlBytes -= bytes;
        }
    }

    public synchronized void removeQueuedControl(Message message) {
        controlDelivered(message);
    }

    public synchronized void clearQueuedControls() {
        queuedControls.clear();
        queuedControlBytes = 0;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public synchronized void complete(Throwable failure) {
        clearQueuedControls();
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
    }
}
