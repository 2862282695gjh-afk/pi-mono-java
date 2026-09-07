/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventProjectorFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.session.compaction.CompactionReason;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionResult;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.cron.AgentScopedCronToolFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * 组合真实注册表、操作锁、Holder、投影器及 Codec 验证已准入压缩，不冒充数据库准入测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCompactionCoordinatorTest {
    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final RuntimeExecutionTimeoutScheduler scheduler = mock(RuntimeExecutionTimeoutScheduler.class);

    private final ScheduledFuture<?> timeoutTask = mock(ScheduledFuture.class);

    private final AgentSessionFactory sessionFactory = mock(AgentSessionFactory.class);

    private final ManagedAgentSession session = mock(ManagedAgentSession.class);

    private final Agent agent = mock(Agent.class);

    private final Runnable unsubscribe = mock(Runnable.class);

    private final AtomicReference<Consumer<SessionCompactionEvent>> listener = new AtomicReference<>();

    private final AtomicReference<Runnable> timeout = new AtomicReference<>();

    private final CompletableFuture<SessionCompactionResult> compaction = new CompletableFuture<>();

    private final RuntimeCompactionExecution execution = new RuntimeCompactionExecution();

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    private final Model model = mock(Model.class);

    private final AgentDirectorySnapshotDTO snapshot = new AgentDirectorySnapshotDTO(
            "agent", "model", List.of("model"), Path.of("/agent"), Path.of("/agent/.campusclaw"));

    private final RuntimeSessionEngineRegistry registry;

    private final RuntimeSessionHolder holder;

    private final RuntimeEventProjectorFactory projectors;

    private final RuntimeCompactionCoordinator coordinator;

    RuntimeCompactionCoordinatorTest() {
        RuntimeExecutionProperties properties = new RuntimeExecutionProperties();
        properties.setMaxActive(1);
        registry = new RuntimeSessionEngineRegistry(
                sessionFactory,
                mock(SubagentExecutionService.class),
                mock(AgentScopedCronToolFactory.class),
                properties);
        when(sessionFactory.create(any())).thenReturn(session);
        when(session.agent()).thenReturn(agent);
        when(session.compact(null)).thenReturn(compaction);
        when(session.subscribeCompaction(any())).thenAnswer(call -> {
            listener.set(call.getArgument(0));
            return unsubscribe;
        });
        when(scheduler.schedule(any(), any())).thenAnswer(call -> {
            timeout.set(call.getArgument(0));
            return timeoutTask;
        });
        holder = register("session", execution);
        execution.beginRun("internal-usage-run");
        Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
        RuntimeEntryCodec codec =
                new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());
        AtomicInteger ids = new AtomicInteger();
        projectors =
                spy(new RuntimeEventProjectorFactory(repository, codec, () -> "entry-" + ids.incrementAndGet(), clock));
        coordinator = new RuntimeCompactionCoordinator(registry, repository, projectors, scheduler, properties, clock);
        when(repository.listCurrentBranchEntries("session", 0L, 500))
                .thenReturn(List.of(
                        codec.userEntry("session", "old", "old", List.of(), now),
                        codec.userEntry("session", "kept", "kept", List.of(), now)));
        when(repository.appendEntryWithUsage(any(), any(), any())).thenAnswer(call -> {
            RuntimeEntryDTO persisted = new RuntimeEntryDTO();
            persisted.setEntrySeq(41L);
            return persisted;
        });
    }

    @Test
    void shouldRejectControlsBeforeRegistrationAndUseSharedCapacity() {
        assertThat(execution.acceptingControls()).isFalse();
        assertThat(execution.queueControl(new UserMessage("control", 1L), 7L, 10, 100L))
                .isFalse();
        assertThatThrownBy(() -> register("other", new RuntimeCompactionExecution()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED));
        var result = start();
        assertThat(result).isNotDone();
        verify(scheduler).schedule(any(), eq(Duration.ofMinutes(30)));
        succeed();
        assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
        assertThat(register("other", new RuntimeCompactionExecution()).sessionId())
                .isEqualTo("other");
    }

    @Test
    void shouldCommitAuthoritativeSequenceAndCleanUpBeforeCompletingWithoutQueueContinuation() {
        var result = start();
        AtomicReference<Boolean> releasedAtCompletion = new AtomicReference<>();
        result.thenRun(() -> releasedAtCompletion.set(registry.find("session").isEmpty()));

        succeed();

        assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
        assertThat(releasedAtCompletion.get()).isTrue();
        assertThat(execution.completion()).isCompleted();
        var order = inOrder(repository, unsubscribe, session);
        order.verify(repository).appendEntryWithUsage(any(), any(), any());
        order.verify(unsubscribe).run();
        order.verify(session).close();
        order.verify(repository).finishExecution("session", now);
        ArgumentCaptor<RuntimeEntryDTO> entry = ArgumentCaptor.forClass(RuntimeEntryDTO.class);
        ArgumentCaptor<RuntimeRecordDTO> record = ArgumentCaptor.forClass(RuntimeRecordDTO.class);
        verify(repository).appendEntryWithUsage(entry.capture(), record.capture(), eq(Usage.empty()));
        assertThat(entry.getValue().getType()).isEqualTo("session.compaction.completed");
        assertThat(entry.getValue().getPayload()).contains("\"firstKeptEntryId\":\"kept\"");
        assertThat(record.getValue().getRunId()).isEqualTo("internal-usage-run");
        verify(repository, never()).appendEntry(any());
        verify(session, never()).continueQueuedExecution();
        verify(agent, never()).hasQueuedControlMessages();
        verify(agent, never()).subscribe(any());
        verify(timeoutTask).cancel(false);
    }

    @Test
    void shouldNotPropagateClientCancellationToAcceptedCompaction() {
        var detached = start();
        assertThat(detached.cancel(true)).isTrue();
        assertThat(compaction).isNotDone();
        assertThat(execution.completion()).isNotDone();
        assertThat(registry.find("session")).contains(holder);
        verify(session, never()).abort();
        verify(session, never()).close();

        succeed();

        assertThat(detached).isCancelled();
        assertThat(execution.result().toCompletableFuture().join())
                .isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
        verify(repository).finishExecution("session", now);
    }

    @Test
    void shouldNotAllowCallerToForgeTheAuthoritativeResult() {
        var detached = start();
        assertThat(detached.complete(new RuntimeCompactionResultDTO(false, null)))
                .isTrue();
        assertThat(execution.result().toCompletableFuture()).isNotDone();

        succeed();

        assertThat(execution.result().toCompletableFuture().join())
                .isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"projector", "subscribe", "schedule", "compact"})
    void shouldCleanUpSynchronousStartupFailures(String stage) {
        IllegalStateException error = new IllegalStateException(stage);
        switch (stage) {
            case "projector" -> doThrow(error).when(projectors).createForCompaction(any(), any(), any());
            case "subscribe" -> doThrow(error).when(session).subscribeCompaction(any());
            case "schedule" -> doThrow(error).when(scheduler).schedule(any(), any());
            case "compact" -> doThrow(error).when(session).compact(null);
            default -> throw new AssertionError("unexpected fixture stage");
        }

        var result = start();

        assertThatThrownBy(result::join).hasCause(error);
        assertReleased();
        verify(repository, never()).appendEntryWithUsage(any(), any(), any());
        if (stage.equals("schedule") || stage.equals("compact")) {
            verify(unsubscribe).run();
        }
    }

    @Test
    void shouldCleanUpAsynchronousFailureAndIgnoreDuplicatedTimeout() {
        var result = start();
        IllegalStateException error = new IllegalStateException("provider failure");

        compaction.completeExceptionally(error);
        timeout.get().run();

        assertThatThrownBy(result::join).hasCause(error);
        assertThat(execution.timedOut()).isFalse();
        assertReleased();
        verify(repository, never()).appendEntryWithUsage(any(), any(), any());
    }

    @Test
    void shouldRejectSuccessfulFutureWithoutAnAuthoritativeEntry() {
        var result = start();

        compaction.complete(compactionResult());

        assertThatThrownBy(result::join)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("compaction completed without an authoritative entry");
        verify(repository, never()).appendEntryWithUsage(any(), any(), any());
        assertReleased();
    }

    @Test
    void shouldExposeProjectionFailureInsteadOfSuccess() {
        IllegalStateException error = new IllegalStateException("database unavailable");
        doThrow(error).when(repository).appendEntryWithUsage(any(), any(), any());
        var result = start();

        succeed();

        assertThatThrownBy(result::join).hasCause(error);
        verify(session).abort();
        assertReleased();
    }

    @Test
    void shouldPreserveAllCleanupFailuresAndStillReleaseCapacity() {
        IllegalStateException unsubscribeError = new IllegalStateException("unsubscribe");
        IllegalStateException closeError = new IllegalStateException("close");
        IllegalStateException persistenceError = new IllegalStateException("finish persistence");
        doThrow(unsubscribeError).when(unsubscribe).run();
        doThrow(closeError).when(session).close();
        doThrow(persistenceError).when(repository).finishExecution("session", now);
        var result = start();

        succeed();

        assertThatThrownBy(result::join).hasCause(unsubscribeError);
        assertThat(unsubscribeError.getSuppressed()).containsExactly(closeError, persistenceError);
        assertReleased();
        assertThat(register("next", new RuntimeCompactionExecution()).sessionId())
                .isEqualTo("next");
    }

    @Test
    void shouldForceTimeoutCleanupEvenIfUnderlyingFutureDoesNotCooperate() {
        var result = start();

        timeout.get().run();
        timeout.get().run();

        assertThatThrownBy(result::join).hasCauseInstanceOf(TimeoutException.class);
        assertThat(execution.timedOut()).isTrue();
        assertThat(compaction).isNotDone();
        assertReleased();
        succeed();
        verify(repository, never()).appendEntryWithUsage(any(), any(), any());
        verify(repository).finishExecution("session", now);
        verify(timeoutTask).cancel(false);
        assertThatThrownBy(execution.result().toCompletableFuture()::join).hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void shouldHandleTimeoutTriggeredBeforeSchedulingReturns() {
        when(scheduler.schedule(any(), any())).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return timeoutTask;
        });

        var result = start();

        assertThatThrownBy(result::join).hasCauseInstanceOf(TimeoutException.class);
        assertReleased();
        verify(session, never()).compact(null);
        verify(timeoutTask).cancel(false);
    }

    @Test
    void shouldIgnoreReentrantCancellationWhileTimeoutClosesTheSession() {
        doAnswer(call -> compaction.cancel(true)).when(session).close();
        var result = start();

        timeout.get().run();

        assertThatThrownBy(result::join).hasCauseInstanceOf(TimeoutException.class);
        assertThat(compaction).isCancelled();
        assertReleased();
        verify(unsubscribe).run();
    }

    @Test
    void shouldNotReleaseAnotherExecutionOrDoubleReleaseTheSameHolder() {
        registry.complete(holder, new RuntimeCompactionExecution());
        assertThat(registry.find("session")).contains(holder);
        assertThat(holder.activeExecution()).contains(execution);
        verify(session, never()).close();
        var result = start();
        succeed();

        registry.complete(holder, execution);

        assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
        assertReleased();
        register("next", new RuntimeCompactionExecution());
        assertThatThrownBy(() -> register("excess", new RuntimeCompactionExecution()))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED));
    }

    @Test
    void shouldIgnoreStaleTimeoutAndLateProjectionAfterSuccess() {
        var result = start();
        succeed();

        timeout.get().run();
        listener.get().accept(completedEvent());

        assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
        assertThat(execution.timedOut()).isFalse();
        verify(repository).appendEntryWithUsage(any(), any(), any());
        assertReleased();
    }

    @Test
    void shouldCompleteServerCancellationWithoutDefiningAnHttpInterruptContract() {
        var result = start();
        execution.requestAbort();

        compaction.cancel(true);

        assertThatThrownBy(result::join).hasCauseInstanceOf(CancellationException.class);
        assertThat(compaction).isCancelled();
        assertReleased();
    }

    @Test
    void shouldSerializeProjectionAndCompletionWithExistingSessionOperations() throws Exception {
        var result = start();
        CountDownLatch entered = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> work = registry.withOperationLock("session", () -> {
                var pending = CompletableFuture.runAsync(
                        () -> {
                            entered.countDown();
                            succeed();
                        },
                        workers);
                try {
                    assertThat(entered.await(1L, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                assertThat(pending).isNotDone();
                assertThat(result).isNotDone();
                verify(repository, never()).appendEntryWithUsage(any(), any(), any());
                return pending;
            });
            work.get(2L, TimeUnit.SECONDS);
        }
        assertThat(result.get(1L, TimeUnit.SECONDS)).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
        assertReleased();
    }

    @Test
    void shouldRejectDuplicateStartWithoutDisturbingTheFirstExecution() {
        var result = start();

        assertThatThrownBy(this::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("compaction execution is already started or completed");
        assertThat(result).isNotDone();
        verify(session).compact(null);
        verify(session, never()).close();
        succeed();
        assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
    }

    @Test
    void shouldRejectAnExecutionThatDoesNotOwnTheHolder() {
        assertThatThrownBy(() -> coordinator.start(holder, new RuntimeCompactionExecution(), Locale.US))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("compaction execution does not own the holder");
        assertThat(registry.find("session")).contains(holder);
        verify(session, never()).compact(null);
        verify(repository, never()).finishExecution(any(), any());
    }

    private CompletableFuture<RuntimeCompactionResultDTO> start() {
        return coordinator.start(holder, execution, Locale.US).toCompletableFuture();
    }

    @Test
    void shouldInterruptOnlyTheBoundExecutionAndReleaseWithoutCooperativeCompletion() {
        var result = start();
        var call = new RuntimeCompactionCall(execution.result(), execution::interrupt);

        assertThat(call.interrupt()).isTrue();

        assertThatThrownBy(result::join).hasCauseInstanceOf(CancellationException.class);
        assertThat(compaction).isNotDone();
        assertThat(execution.abortRequested()).isTrue();
        assertReleased();
        var replacement = new RuntimeCompactionExecution();
        var next = register("session", replacement);
        assertThat(call.interrupt()).isFalse();
        assertThat(registry.find("session")).containsSame(next);
        assertThat(replacement.abortRequested()).isFalse();
        succeed();
        verify(repository, never()).appendEntryWithUsage(any(), any(), any());
        verify(repository).finishExecution("session", now);
        registry.complete(next, replacement);
    }

    @Test
    void shouldRejectAStaleInterruptBindingEvenWhenItsResultIsStillPending() {
        var result = start();
        registry.complete(holder, execution);
        var replacement = new RuntimeCompactionExecution();
        var next = register("session", replacement);

        assertThat(execution.interrupt()).isFalse();

        assertThat(result).isNotDone();
        assertThat(execution.abortRequested()).isFalse();
        assertThat(replacement.abortRequested()).isFalse();
        assertThat(registry.find("session")).containsSame(next);
        verify(repository, never()).finishExecution(any(), any());
        registry.complete(next, replacement);
    }

    @Test
    void shouldSerializeExplicitInterruptBehindTheSharedOperationLock() throws Exception {
        var result = start();
        CountDownLatch entered = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = registry.withOperationLock("session", () -> {
                var attempt = CompletableFuture.supplyAsync(
                        () -> {
                            entered.countDown();
                            return execution.interrupt();
                        },
                        workers);
                try {
                    assertThat(entered.await(2L, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                assertThat(attempt).isNotDone();
                assertThat(execution.abortRequested()).isFalse();
                return attempt;
            });
            assertThat(pending.get(2L, TimeUnit.SECONDS)).isTrue();
        }
        assertThatThrownBy(result::join).hasCauseInstanceOf(CancellationException.class);
        assertReleased();
    }

    private void succeed() {
        listener.get().accept(completedEvent());
        compaction.complete(compactionResult());
    }

    private static SessionCompactionCompletedEvent completedEvent() {
        return new SessionCompactionCompletedEvent(CompactionReason.MANUAL, compactionResult(), false);
    }

    private static SessionCompactionResult compactionResult() {
        return new SessionCompactionResult("summary", List.of(new UserMessage("kept", 1L)), 1, 100, 20, Usage.empty());
    }

    private RuntimeSessionHolder register(String sessionId, RuntimeCompactionExecution active) {
        return registry.register(
                sessionId, snapshot, model, false, List.of(), active, MateCredentials.appKey("caller", "key", "token"));
    }

    private void assertReleased() {
        assertThat(registry.find("session")).isEmpty();
        assertThat(holder.activeExecution()).isEmpty();
        assertThat(execution.completion()).isDone();
        assertThat(execution.interrupt()).isFalse();
        verify(session).close();
        verify(repository).finishExecution("session", now);
    }
}
