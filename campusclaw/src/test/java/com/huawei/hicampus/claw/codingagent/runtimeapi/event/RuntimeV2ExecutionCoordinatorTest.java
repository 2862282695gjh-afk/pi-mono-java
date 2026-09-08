/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeExecutionTimeoutScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeTerminalRetryScheduler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * v2 执行协调器的固定目标终态与资源收尾测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeV2ExecutionCoordinatorTest {
    @Test
    void shouldCommitDoneIdleBeforeCompletingExecution() {
        Fixture fixture = fixture();
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        fixture.work.complete(null);

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.DONE);
        assertThat(terminal.event().getType()).isEqualTo("session.status_idle");
        assertThat(terminal.event().getPayload()).contains("\"reason\":\"done\"", "\"sourceEventId\":\"event-root\"");
        RuntimeSseEventVO frame = emittedFrame(fixture.output);
        assertThat(frame.isDataOnly()).isTrue();
        assertThat(frame.getData())
                .containsEntry("type", "session.status_idle")
                .containsEntry("reason", "done")
                .containsEntry("sourceEventId", "event-root");
        assertReleased(fixture);
        assertThat(fixture.execution.completion()).isCompleted();
    }

    @Test
    void shouldPersistAcceptedStartFailureWithoutEscaping() {
        Fixture fixture = fixture();
        when(fixture.projectors.create(fixture.holder, fixture.execution, fixture.message, Locale.US))
                .thenThrow(new IllegalStateException("projector unavailable"));

        assertThatCode(() -> fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US))
                .doesNotThrowAnyException();

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.FAILED);
        assertThat(terminal.event().getPayload())
                .contains("\"errorCode\":\"EXECUTION_START_FAILED\"")
                .contains("The message was accepted, but execution could not start.");
        verify(fixture.timeouts, never()).schedule(any(), any());
        verify(fixture.engines).complete(fixture.holder, fixture.execution);
        verify(fixture.output).complete();
        assertThat(fixture.execution.completion()).isCompletedExceptionally();
    }

    @Test
    void shouldReleaseRegisteredListenerWhenSecondRegistrationFails() {
        Fixture fixture = fixture();
        when(fixture.holder.subscribeCompaction(any())).thenThrow(new IllegalStateException("listener unavailable"));

        assertThatCode(() -> fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US))
                .doesNotThrowAnyException();

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.FAILED);
        assertThat(terminal.event().getPayload()).contains("\"errorCode\":\"EXECUTION_START_FAILED\"");
        verify(fixture.agentSubscription).run();
        verify(fixture.compactionSubscription, never()).run();
        verify(fixture.engines).complete(fixture.holder, fixture.execution);
        verify(fixture.output).complete();
    }

    @Test
    void shouldReleaseStartedResourcesWhenPromptFailsToStart() {
        Fixture fixture = fixture();
        when(fixture.holder.prompt(fixture.message)).thenThrow(new IllegalStateException("prompt unavailable"));

        assertThatCode(() -> fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US))
                .doesNotThrowAnyException();

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.FAILED);
        assertThat(terminal.event().getPayload()).contains("\"errorCode\":\"EXECUTION_START_FAILED\"");
        assertReleased(fixture);
        assertThat(fixture.execution.completion()).isCompletedExceptionally();
    }

    @Test
    void shouldRetainHolderAndRetrySameTerminalWhenCommitFails() {
        Fixture fixture = fixture();
        when(fixture.persistence.commitTerminal(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("terminal unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(1));
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        fixture.work.complete(null);

        verify(fixture.output, never()).emit(any());
        verify(fixture.engines, never()).complete(any(), any());
        verify(fixture.agentSubscription).run();
        verify(fixture.compactionSubscription).run();
        verify(fixture.timeout).cancel(false);
        verify(fixture.output).complete();
        assertThat(fixture.execution.terminalRetryPending()).isTrue();
        assertThat(fixture.execution.completion()).isNotDone();

        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.terminalRetries).schedule(retry.capture(), eq(Duration.ofSeconds(1)));
        retry.getValue().run();

        ArgumentCaptor<CommittedEventDTO> attempts = ArgumentCaptor.forClass(CommittedEventDTO.class);
        verify(fixture.persistence, times(2)).commitTerminal(any(), any(), attempts.capture(), any(), any());
        assertThat(attempts.getAllValues())
                .extracting(CommittedEventDTO::getEventId, CommittedEventDTO::getCreatedAt)
                .containsOnly(tuple("event-idle", OffsetDateTime.parse("2026-09-08T02:00:00Z")));
        verify(fixture.engines).complete(fixture.holder, fixture.execution);
        assertThat(fixture.execution.terminalRetryPending()).isFalse();
        assertThat(fixture.execution.completion()).isCompleted();
    }

    @Test
    void shouldReleaseResourcesWhenTerminalProjectionFails() {
        Fixture fixture = fixture();
        when(fixture.projector.terminalReason()).thenThrow(new IllegalStateException("terminal state unavailable"));
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        fixture.work.complete(null);

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.FAILED);
        assertThat(terminal.event().getPayload()).contains("\"errorCode\":\"AGENT_EXECUTION_FAILED\"");
        assertReleased(fixture);
        assertThat(fixture.execution.completion()).isCompletedExceptionally();
    }

    @Test
    void shouldKeepNaturalDoneWhenInterruptWasAcceptedFirst() {
        Fixture fixture = fixture();
        fixture.execution.requestAbort();
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        fixture.work.complete(null);

        assertThat(captureTerminal(fixture).reason()).isEqualTo(RuntimeExecutionTerminalReason.DONE);
    }

    @Test
    void shouldKeepNaturalFailureWhenInterruptWasAcceptedFirst() {
        Fixture fixture = fixture();
        fixture.execution.requestAbort();
        when(fixture.projector.terminalReason()).thenReturn(StopReason.ERROR);
        when(fixture.projector.terminalErrorCode()).thenReturn("MODEL_REQUEST_FAILED");
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        fixture.work.complete(null);

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.FAILED);
        assertThat(terminal.event().getPayload()).contains("\"errorCode\":\"MODEL_REQUEST_FAILED\"");
    }

    @Test
    void shouldPreservePayloadCapacityErrorFromProjector() {
        Fixture fixture = fixture();
        when(fixture.projector.failure()).thenReturn(new IllegalStateException("payload too large"));
        when(fixture.projector.terminalReason()).thenReturn(StopReason.ERROR);
        when(fixture.projector.terminalErrorCode()).thenReturn("EVENT_PAYLOAD_TOO_LARGE");
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        fixture.work.complete(null);

        TerminalCaptureDTO terminal = captureTerminal(fixture);
        assertThat(terminal.reason()).isEqualTo(RuntimeExecutionTerminalReason.FAILED);
        assertThat(terminal.event().getPayload()).contains("\"errorCode\":\"EVENT_PAYLOAD_TOO_LARGE\"");
    }

    @Test
    void shouldInstallProjectorAsTrustedConfirmationHandlerBeforePrompt() throws Exception {
        Fixture fixture = fixture();
        ToolCall call = new ToolCall("call-confirm", "CallMateTool", Map.of("tool", "ask-tool", "args", Map.of()));
        RuntimeToolPermissionPolicy policy = mock(RuntimeToolPermissionPolicy.class);
        when(policy.decide(call)).thenReturn(RuntimeToolPermissionPolicy.Decision.ASK);
        fixture.execution.bindToolPermissions(policy);
        BeforeToolCallContext context = new BeforeToolCallContext(null, call, call.arguments(), null);
        when(fixture.projector.beforeToolCall(context)).thenReturn(BeforeToolCallResult.allow());

        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);

        assertThat(fixture.execution.beforeToolCall(context)).isEqualTo(BeforeToolCallResult.allow());
        verify(fixture.projector).beforeToolCall(context);
    }

    @Test
    void shouldAbortAgentBeforeReleasingConfirmationOnTimeout() {
        Fixture fixture = fixture();
        var pending = fixture.execution.beginToolConfirmation("call-timeout");
        doAnswer(invocation -> {
                    assertThat(pending).isNotDone();
                    return null;
                })
                .when(fixture.holder)
                .abort();
        fixture.coordinator.start(fixture.holder, fixture.execution, fixture.message, Locale.US);
        ArgumentCaptor<Runnable> timeoutAction = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.timeouts).schedule(timeoutAction.capture(), eq(Duration.ofMinutes(30)));

        timeoutAction.getValue().run();

        verify(fixture.holder).abort();
        assertThat(fixture.execution.timedOut()).isTrue();
        assertThatThrownBy(pending::join).hasCauseInstanceOf(TimeoutException.class);
    }

    private static Fixture fixture() {
        RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        RuntimeExecutionPersistenceService persistence = mock(RuntimeExecutionPersistenceService.class);
        RuntimeV2EventProjectorFactory projectors = mock(RuntimeV2EventProjectorFactory.class);
        RuntimeExecutionTimeoutScheduler timeouts = mock(RuntimeExecutionTimeoutScheduler.class);
        RuntimeTerminalRetryScheduler terminalRetries = mock(RuntimeTerminalRetryScheduler.class);
        RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        RuntimeV2EventProjector projector = mock(RuntimeV2EventProjector.class);
        RuntimeEventOutput output = mock(RuntimeEventOutput.class);
        RuntimeActiveExecution execution = execution(output);
        UserMessage message = new UserMessage("question", 1L);
        CompletableFuture<Void> work = new CompletableFuture<>();
        Runnable agentSubscription = mock(Runnable.class);
        Runnable compactionSubscription = mock(Runnable.class);
        ScheduledFuture<?> timeout = mock(ScheduledFuture.class);
        configureRuntime(
                engines,
                projectors,
                timeouts,
                holder,
                projector,
                execution,
                message,
                work,
                agentSubscription,
                compactionSubscription,
                timeout);
        RuntimeV2ExecutionCoordinator coordinator =
                coordinator(engines, persistence, projectors, timeouts, terminalRetries);
        return new Fixture(
                engines,
                persistence,
                projectors,
                timeouts,
                terminalRetries,
                holder,
                projector,
                execution,
                output,
                message,
                work,
                agentSubscription,
                compactionSubscription,
                timeout,
                coordinator);
    }

    private static RuntimeActiveExecution execution(RuntimeEventOutput output) {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(output);
        execution.bindTarget(new ExecutionTargetDTO("session-v2", "execution-v2", "event-root", "segment-v2"));
        execution.beginRun("execution-v2");
        return execution;
    }

    private static void configureRuntime(
            RuntimeSessionEngineRegistry engines,
            RuntimeV2EventProjectorFactory projectors,
            RuntimeExecutionTimeoutScheduler timeouts,
            RuntimeSessionHolder holder,
            RuntimeV2EventProjector projector,
            RuntimeActiveExecution execution,
            UserMessage message,
            CompletableFuture<Void> work,
            Runnable agentSubscription,
            Runnable compactionSubscription,
            ScheduledFuture<?> timeout) {
        Agent agent = mock(Agent.class);
        when(holder.sessionId()).thenReturn("session-v2");
        when(holder.agent()).thenReturn(agent);
        when(holder.prompt(message)).thenReturn(work);
        when(holder.activeExecution()).thenReturn(Optional.of(execution));
        when(agent.subscribe(any())).thenReturn(agentSubscription);
        when(holder.subscribeCompaction(any())).thenReturn(compactionSubscription);
        when(projectors.create(holder, execution, message, Locale.US)).thenReturn(projector);
        when(projector.terminalReason()).thenReturn(StopReason.STOP);
        doReturn(timeout).when(timeouts).schedule(any(), eq(Duration.ofMinutes(30)));
        doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return null;
                })
                .when(engines)
                .withOperationLock(eq("session-v2"), any(Runnable.class));
    }

    private static RuntimeV2ExecutionCoordinator coordinator(
            RuntimeSessionEngineRegistry engines,
            RuntimeExecutionPersistenceService persistence,
            RuntimeV2EventProjectorFactory projectors,
            RuntimeExecutionTimeoutScheduler timeouts,
            RuntimeTerminalRetryScheduler terminalRetries) {
        ObjectMapper mapper = new ObjectMapper();
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        return new RuntimeV2ExecutionCoordinator(
                engines,
                persistence,
                projectors,
                timeouts,
                terminalRetries,
                new RuntimeExecutionProperties(),
                new RuntimeEntryCodec(mapper, messages),
                () -> "event-idle",
                new RuntimeCommittedEventFactory(mapper, messages),
                new RuntimeV2EventEncoder(new CommittedEventProjection(mapper), mapper),
                Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));
    }

    private static TerminalCaptureDTO captureTerminal(Fixture fixture) {
        ArgumentCaptor<CommittedEventDTO> event = ArgumentCaptor.forClass(CommittedEventDTO.class);
        ArgumentCaptor<RuntimeExecutionTerminalReason> reason =
                ArgumentCaptor.forClass(RuntimeExecutionTerminalReason.class);
        verify(fixture.persistence)
                .commitTerminal(
                        eq(fixture.execution.target()),
                        any(RuntimeEntryDTO.class),
                        event.capture(),
                        reason.capture(),
                        any());
        return new TerminalCaptureDTO(event.getValue(), reason.getValue());
    }

    @SuppressWarnings("unchecked")
    private static RuntimeSseEventVO emittedFrame(RuntimeEventOutput output) {
        ArgumentCaptor<Supplier<RuntimeSseEventVO>> event = ArgumentCaptor.forClass(Supplier.class);
        verify(output).emit(event.capture());
        return event.getValue().get();
    }

    private static void assertReleased(Fixture fixture) {
        verify(fixture.engines).complete(fixture.holder, fixture.execution);
        verify(fixture.agentSubscription).run();
        verify(fixture.compactionSubscription).run();
        verify(fixture.timeout).cancel(false);
        verify(fixture.output).complete();
    }

    private record TerminalCaptureDTO(CommittedEventDTO event, RuntimeExecutionTerminalReason reason) {}

    private record Fixture(
            RuntimeSessionEngineRegistry engines,
            RuntimeExecutionPersistenceService persistence,
            RuntimeV2EventProjectorFactory projectors,
            RuntimeExecutionTimeoutScheduler timeouts,
            RuntimeTerminalRetryScheduler terminalRetries,
            RuntimeSessionHolder holder,
            RuntimeV2EventProjector projector,
            RuntimeActiveExecution execution,
            RuntimeEventOutput output,
            UserMessage message,
            CompletableFuture<Void> work,
            Runnable agentSubscription,
            Runnable compactionSubscription,
            ScheduledFuture<?> timeout,
            RuntimeV2ExecutionCoordinator coordinator) {}
}
