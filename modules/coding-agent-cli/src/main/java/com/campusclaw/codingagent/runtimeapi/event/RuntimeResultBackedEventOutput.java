/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.Objects;
import java.util.function.Supplier;

import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

/**
 * 仅直推预览帧，并用权威段补读交付完整事件的确认续跑输出。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeResultBackedEventOutput implements RuntimeEventOutput {
    private final RuntimeEventStream stream;

    private final ExecutionTargetDTO target;

    private final RuntimeResultPollingService results;

    public RuntimeResultBackedEventOutput(
            RuntimeEventStream stream, ExecutionTargetDTO target, RuntimeResultPollingService results) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.target = Objects.requireNonNull(target, "target");
        this.results = Objects.requireNonNull(results, "results");
    }

    @Override
    public void emit(Supplier<RuntimeSseEventVO> event) {
        Objects.requireNonNull(event, "event");
        results.notifyCommitted(target);
    }

    @Override
    public void emitBestEffort(Supplier<RuntimeSseEventVO> event) {
        stream.emitBestEffort(event);
    }

    @Override
    public void complete() {
        results.notifyCommitted(target);
    }
}
