/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.compaction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;

/**
 * 无请求流、禁止控制输入的压缩执行句柄，隔离调用方取消与 Runtime 终态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeCompactionExecution extends RuntimeActiveExecution {
    private final CompletableFuture<RuntimeCompactionResultDTO> result = new CompletableFuture<>();

    private boolean started;

    public RuntimeCompactionExecution() {
        super(RuntimeEventOutput.persistenceOnly());
        closeControls();
    }

    public CompletionStage<RuntimeCompactionResultDTO> result() {
        return result.minimalCompletionStage();
    }

    synchronized void beginCompaction() {
        if (started || completion().isDone()) {
            throw new IllegalStateException("compaction execution is already started or completed");
        }
        runId();
        started = true;
    }

    void finishCompaction(Long sequence, Throwable failure) {
        complete(failure);
        if (failure == null) {
            result.complete(new RuntimeCompactionResultDTO(true, sequence));
        } else {
            result.completeExceptionally(failure);
        }
    }
}
