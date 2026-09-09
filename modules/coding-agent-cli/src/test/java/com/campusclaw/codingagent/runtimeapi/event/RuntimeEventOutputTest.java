/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.junit.jupiter.api.Test;

/**
 * 验证无请求输出不求值、不缓冲，以及 SSE 输出适配仍同步保序。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventOutputTest {
    @Test
    void shouldDiscardFactoriesWithoutEvaluatingOrRetainingRequestOutput() {
        RuntimeEventOutput output = RuntimeEventOutput.persistenceOnly();
        Supplier<RuntimeSseEventVO> event = () -> {
            throw new AssertionError("persistence-only output must not construct SSE data");
        };

        assertDoesNotThrow(() -> {
            output.emit(event);
            output.emitBestEffort(event);
            output.complete();
            output.emit(event);
        });
    }

    @Test
    void shouldKeepCompletionIndependentAcrossExecutionsSharingNoOutput() {
        RuntimeActiveExecution first = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        RuntimeActiveExecution second = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());

        first.output().complete();
        assertThat(first.completion()).isNotDone();
        first.complete(null);

        assertThat(first.completion()).isCompleted();
        assertThat(second.completion()).isNotDone();
    }

    @Test
    void shouldDeliverLazySseEventsSynchronouslyAndInOriginalOrder() {
        AtomicInteger sized = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        RuntimeEventStream stream =
                new RuntimeEventStream(8, 1024L, Duration.ofSeconds(15), event -> sized.incrementAndGet());
        RuntimeEventOutput output = stream;
        RuntimeSseEventVO persisted =
                new RuntimeSseEventVO("41", "session.compaction.completed", Map.of("summary", "ok"));
        RuntimeSseEventVO preview = new RuntimeSseEventVO(null, "tool.execution.delta", Map.of("delta", "next"));

        output.emit(() -> {
            created.incrementAndGet();
            return persisted;
        });
        output.emitBestEffort(() -> {
            created.incrementAndGet();
            return preview;
        });
        assertThat(created.get()).isEqualTo(2);
        output.complete();
        RuntimeEventSubscriber subscriber = mock(RuntimeEventSubscriber.class);
        stream.attach(Runnable::run, subscriber);

        var order = inOrder(subscriber);
        order.verify(subscriber).onEvent(persisted);
        order.verify(subscriber).onEvent(preview);
        order.verify(subscriber).onComplete();
        order.verifyNoMoreInteractions();
        assertThat(sized.get()).isEqualTo(2);
    }
}
