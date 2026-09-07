/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.campusclaw.agent.Agent;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;

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
        assertThat(second.acceptingControls()).isTrue();
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

    @Test
    void shouldSkipTerminalMessageLocalizationWithoutRequestOutput() {
        MessageSource messages = mock(MessageSource.class);
        RuntimeTerminalEventFactory factory = new RuntimeTerminalEventFactory(messages);
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());

        assertDoesNotThrow(() -> {
            factory.emit(execution.output(), execution, StopReason.STOP, null, Locale.US);
            factory.emit(
                    execution.output(), execution, StopReason.ERROR, new IllegalStateException("failed"), Locale.US);
        });

        verifyNoInteractions(messages);
        assertThat(execution.completion()).isNotDone();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldFinalizeCoordinatorWithoutRequestStream(boolean failed) {
        RuntimeSessionEngineRegistry registry = mock(RuntimeSessionEngineRegistry.class);
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        RuntimeExecutionTimeoutScheduler scheduler = mock(RuntimeExecutionTimeoutScheduler.class);
        RuntimeEventProjectorFactory projectors = mock(RuntimeEventProjectorFactory.class);
        RuntimeEventProjector projector = mock(RuntimeEventProjector.class);
        Agent agent = mock(Agent.class);
        Runnable unsubscribeAgent = mock(Runnable.class);
        Runnable unsubscribeCompaction = mock(Runnable.class);
        ScheduledFuture<?> timeout = mock(ScheduledFuture.class);
        CompletableFuture<Void> work = new CompletableFuture<>();
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        UserMessage message = new UserMessage("queued continuation", 1L);
        when(holder.sessionId()).thenReturn("session");
        when(holder.agent()).thenReturn(agent);
        when(holder.prompt(message)).thenReturn(work);
        when(agent.subscribe(any())).thenReturn(unsubscribeAgent);
        when(holder.subscribeCompaction(any())).thenReturn(unsubscribeCompaction);
        when(projectors.create(holder, execution, message, Locale.US)).thenReturn(projector);
        when(projector.terminalReason()).thenReturn(StopReason.STOP);
        when(scheduler.schedule(any(), eq(Duration.ofMinutes(30)))).thenAnswer(call -> timeout);
        doAnswer(call -> {
                    call.<Runnable>getArgument(1).run();
                    return null;
                })
                .when(registry)
                .withOperationLock(eq("session"), any(Runnable.class));
        var coordinator = new RuntimeExecutionCoordinator(
                registry,
                repository,
                projectors,
                scheduler,
                new RuntimeExecutionProperties(),
                new RuntimeTerminalEventFactory(mock(MessageSource.class)),
                Clock.systemUTC());

        coordinator.start(holder, execution, message, Locale.US);
        assertThat(execution.completion()).isNotDone();
        completeWorkAndAssertOutcome(work, execution, failed);
        verify(repository).finishExecution(eq("session"), any());
        verify(registry).complete(holder, execution);
        verify(unsubscribeAgent).run();
        verify(unsubscribeCompaction).run();
        verify(timeout).cancel(false);
        assertThat(execution.acceptingControls()).isFalse();
    }

    private static void completeWorkAndAssertOutcome(
            CompletableFuture<Void> work, RuntimeActiveExecution execution, boolean failed) {
        if (failed) {
            work.completeExceptionally(new IllegalStateException("expected failure"));
            assertThat(execution.completion()).isCompletedExceptionally();
        } else {
            work.complete(null);
            assertThat(execution.completion()).isCompleted();
        }
    }
}
