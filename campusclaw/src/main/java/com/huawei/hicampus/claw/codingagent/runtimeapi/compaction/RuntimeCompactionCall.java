/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.compaction;

import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;

/**
 * 单次压缩的隔离完成视图与精确中断能力，不公开 Holder 或可变执行对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeCompactionCall {
    private final CompletionStage<RuntimeCompactionResultDTO> result;

    private final BooleanSupplier interruption;

    RuntimeCompactionCall(CompletionStage<RuntimeCompactionResultDTO> result, BooleanSupplier interruption) {
        this.result = result.toCompletableFuture().minimalCompletionStage();
        this.interruption = interruption;
    }

    public CompletionStage<RuntimeCompactionResultDTO> result() {
        return result;
    }

    public boolean interrupt() {
        return interruption.getAsBoolean();
    }
}
