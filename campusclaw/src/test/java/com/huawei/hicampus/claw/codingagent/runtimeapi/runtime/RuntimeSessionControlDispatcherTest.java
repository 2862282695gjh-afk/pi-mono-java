/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionControlRepository;

import org.junit.jupiter.api.Test;

/**
 * 本机固定执行控制交付的身份、顺序和确认恢复测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionControlDispatcherTest {
    @Test
    void shouldAbortAgentBeforeCancellingPendingConfirmation() {
        Fixture fixture = fixture();
        var pending = fixture.execution.beginToolConfirmation("call-1");
        doAnswer(invocation -> {
                    assertThat(pending).isNotDone();
                    return null;
                })
                .when(fixture.holder)
                .abort();

        assertThat(fixture.dispatcher.dispatchStop(fixture.original)).isTrue();

        verify(fixture.holder).abort();
        assertThat(fixture.execution.abortRequested()).isTrue();
        assertThatThrownBy(pending::join).isInstanceOf(CancellationException.class);
    }

    @Test
    void shouldStageNewStreamThenClaimAndResumeOriginalExecution() {
        Fixture fixture = fixture();
        var pending = fixture.execution.beginToolConfirmation("call-1");
        RuntimeEventOutput continuation = mock(RuntimeEventOutput.class);
        ToolConfirmationDecisionDTO decision = decision();
        when(fixture.controls.claimConfirmation(eq(fixture.original), eq("call-1"), any()))
                .thenReturn(Optional.of(decision));
        when(fixture.controls.acknowledgeConfirmation(eq(decision), any())).thenReturn(true);

        assertThat(fixture.dispatcher.stageConfirmation(fixture.resumed, "call-1", continuation))
                .contains(fixture.original);
        assertThat(fixture.dispatcher.dispatchConfirmation(fixture.original, "call-1"))
                .isTrue();

        assertThat(pending).isCompletedWithValue(decision);
        assertThat(fixture.execution.target()).isEqualTo(fixture.resumed);
        assertThat(fixture.execution.output()).isSameAs(continuation);
        verify(fixture.controls).acknowledgeConfirmation(eq(decision), any());
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        RuntimeExecutionControlRepository controls = mock(RuntimeExecutionControlRepository.class);
        RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        ExecutionTargetDTO original = new ExecutionTargetDTO("session", "execution", "root", "segment-1");
        ExecutionTargetDTO resumed = new ExecutionTargetDTO("session", "execution", "root", "segment-2");
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.bindTarget(original);
        when(engines.find("session")).thenReturn(Optional.of(holder));
        when(holder.activeExecution()).thenReturn(Optional.of(execution));
        doAnswer(invocation -> invocation.<Supplier<Object>>getArgument(1).get())
                .when(engines)
                .withOperationLock(eq("session"), any(Supplier.class));
        return new Fixture(controls, holder, execution, original, resumed, dispatcher(engines, controls));
    }

    private static RuntimeSessionControlDispatcher dispatcher(
            RuntimeSessionEngineRegistry engines, RuntimeExecutionControlRepository controls) {
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T08:00:00Z"), ZoneOffset.UTC);
        return new RuntimeSessionControlDispatcher(engines, controls, clock);
    }

    private static ToolConfirmationDecisionDTO decision() {
        var decision = new ToolConfirmationDecisionDTO();
        decision.setSessionId("session");
        decision.setExecutionId("execution");
        decision.setConfirmationEventId("confirmation");
        decision.setPreviousSegmentId("segment-1");
        decision.setSegmentId("segment-2");
        decision.setToolCallId("call-1");
        return decision;
    }

    private record Fixture(
            RuntimeExecutionControlRepository controls,
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            ExecutionTargetDTO original,
            ExecutionTargetDTO resumed,
            RuntimeSessionControlDispatcher dispatcher) {}
}
