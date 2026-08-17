/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 与客户端订阅生命周期解耦的单次 Session 执行事件流。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class RuntimeEventStream {
    private final Sinks.Many<RuntimeSseEventVO> sink = Sinks.many().replay().all();

    public synchronized void emit(RuntimeSseEventVO event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED) {
            throw new IllegalStateException("failed to emit runtime SSE event: " + result);
        }
    }

    public synchronized void complete() {
        sink.tryEmitComplete();
    }

    public Flux<RuntimeSseEventVO> flux() {
        return sink.asFlux();
    }
}
