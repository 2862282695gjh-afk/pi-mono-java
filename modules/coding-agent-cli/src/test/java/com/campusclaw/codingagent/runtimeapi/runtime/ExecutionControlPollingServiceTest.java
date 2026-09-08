/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.campusclaw.codingagent.runtimeapi.dto.ExecutionControlSignalDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionControlRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ExecutionControlPollingServiceTest {
    private RuntimeExecutionControlRepository controls;

    private RuntimeLocalControlDispatcher dispatcher;

    private ExecutionControlPollingService service;

    private RuntimeExecutionProperties properties;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        controls = mock(RuntimeExecutionControlRepository.class);
        dispatcher = mock(RuntimeLocalControlDispatcher.class);
        ObjectProvider<RuntimeLocalControlDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);
        properties = new RuntimeExecutionProperties();
        service = new ExecutionControlPollingService(controls, provider, properties);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void shouldSkipDatabaseWhenNoLocalExecutionIsActive() {
        when(dispatcher.activeTargets(100)).thenReturn(List.of());

        service.poll();

        verify(controls, never()).findPendingControls(anyList());
    }

    @Test
    void shouldBatchDistinctTargetsAndPreferStopOverConfirmation() {
        ExecutionTargetDTO stopping = target("stopping");
        ExecutionTargetDTO confirming = target("confirming");
        when(dispatcher.activeTargets(100)).thenReturn(List.of(stopping, confirming, stopping));
        when(controls.findPendingControls(List.of(stopping, confirming)))
                .thenReturn(List.of(signal(stopping, true, "ignored-tool"), signal(confirming, false, "tool-1")));
        when(dispatcher.dispatchStop(stopping)).thenReturn(true);
        when(dispatcher.dispatchConfirmation(confirming, "tool-1")).thenReturn(true);

        service.poll();

        verify(controls).findPendingControls(List.of(stopping, confirming));
        verify(dispatcher, timeout(2000)).dispatchStop(stopping);
        verify(dispatcher, timeout(2000)).dispatchConfirmation(confirming, "tool-1");
        verify(dispatcher, never()).dispatchConfirmation(stopping, "ignored-tool");
    }

    @Test
    void shouldNotRepeatSuccessfullyDeliveredSignal() {
        ExecutionTargetDTO target = target("deduplicated");
        when(dispatcher.activeTargets(100)).thenReturn(List.of(target));
        when(controls.findPendingControls(List.of(target))).thenReturn(List.of(signal(target, true, null)));
        when(dispatcher.dispatchStop(target)).thenReturn(true);

        service.poll();
        verify(dispatcher, timeout(2000)).dispatchStop(target);
        service.poll();

        verify(dispatcher, timeout(2000).times(1)).dispatchStop(target);
    }

    @Test
    void shouldRetrySignalThatWasNotConsumedLocally() throws InterruptedException {
        ExecutionTargetDTO target = target("retry");
        when(dispatcher.activeTargets(100)).thenReturn(List.of(target));
        when(controls.findPendingControls(List.of(target))).thenReturn(List.of(signal(target, true, null)));
        when(dispatcher.dispatchStop(target)).thenReturn(false, true);

        service.poll();
        verify(dispatcher, timeout(2000).times(1)).dispatchStop(target);
        pollUntilDispatchedTwice(target);

        verify(dispatcher, timeout(2000).times(2)).dispatchStop(target);
    }

    @Test
    void shouldBackOffAfterDatabaseFailure() {
        ExecutionTargetDTO target = target("backoff");
        when(dispatcher.activeTargets(100)).thenReturn(List.of(target));
        doThrow(new IllegalStateException("database unavailable"))
                .when(controls)
                .findPendingControls(List.of(target));

        service.poll();
        service.poll();

        verify(controls, times(1)).findPendingControls(List.of(target));
        verify(dispatcher, never()).dispatchStop(target);
    }

    @Test
    void shouldShareDeduplicationWithPostCommitFastPath() {
        ExecutionTargetDTO target = target("fast-path");
        when(dispatcher.activeTargets(100)).thenReturn(List.of(target));
        when(controls.findPendingControls(List.of(target))).thenReturn(List.of(signal(target, true, null)));
        when(dispatcher.dispatchStop(target)).thenReturn(true);

        service.dispatchCommittedStop(target);
        verify(dispatcher, timeout(2000)).dispatchStop(target);
        service.poll();

        verify(dispatcher, timeout(2000).times(1)).dispatchStop(target);
    }

    @Test
    void shouldQueryAllActiveTargetsInBoundedBatches() {
        ExecutionTargetDTO first = target("batch-1");
        ExecutionTargetDTO second = target("batch-2");
        properties.setControlPollBatchSize(1);
        when(dispatcher.activeTargets(100)).thenReturn(List.of(first, second));
        when(controls.findPendingControls(List.of(first))).thenReturn(List.of());
        when(controls.findPendingControls(List.of(second))).thenReturn(List.of());

        service.poll();

        verify(controls).findPendingControls(List.of(first));
        verify(controls).findPendingControls(List.of(second));
    }

    private void pollUntilDispatchedTwice(ExecutionTargetDTO target) throws InterruptedException {
        for (int index = 0; index < 20; index++) {
            service.poll();
            try {
                verify(dispatcher, times(2)).dispatchStop(target);
                return;
            } catch (AssertionError ignored) {
                Thread.sleep(Duration.ofMillis(25));
            }
        }
        throw new AssertionError("停止信号未在本机拒绝后重试");
    }

    private static ExecutionTargetDTO target(String suffix) {
        return new ExecutionTargetDTO(
                "session-" + suffix, "execution-" + suffix, "root-" + suffix, "segment-" + suffix);
    }

    private static ExecutionControlSignalDTO signal(
            ExecutionTargetDTO target, boolean stopRequested, String toolCallId) {
        var signal = new ExecutionControlSignalDTO();
        signal.setSessionId(target.sessionId());
        signal.setExecutionId(target.executionId());
        signal.setRootEventId(target.rootEventId());
        signal.setSegmentId(target.segmentId());
        signal.setStopRequested(stopRequested);
        signal.setToolCallId(toolCallId);
        return signal;
    }
}
