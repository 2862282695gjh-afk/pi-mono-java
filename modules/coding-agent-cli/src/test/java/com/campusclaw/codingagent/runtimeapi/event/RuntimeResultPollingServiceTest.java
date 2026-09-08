/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SegmentEventBatchDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionResultRepository;

import org.junit.jupiter.api.Test;

class RuntimeResultPollingServiceTest {
    @Test
    void shouldQuerySameTargetOnceAndDeliverToIndependentResponses() {
        RuntimeExecutionResultRepository results = mock(RuntimeExecutionResultRepository.class);
        RuntimeResultWaitRegistry waits = registry();
        RuntimeResultPollingService service = new RuntimeResultPollingService(results, waits, properties());
        var first = new ArrayList<String>();
        var second = new ArrayList<String>();
        reserveTerminal(waits, first);
        reserveTerminal(waits, second);
        CommittedEventDTO idle = event("idle", 12L);
        when(results.findExecutionTerminal(target())).thenReturn(Optional.of(idle));
        try {
            service.poll();
        } finally {
            service.close();
        }

        verify(results, times(1)).findExecutionTerminal(target());
        assertThat(first).containsExactly("idle");
        assertThat(second).containsExactly("idle");
        assertThat(waits.registeredResponses()).isZero();
    }

    @Test
    void shouldAdvanceSegmentCursorAndCloseOnItsIdle() {
        RuntimeExecutionResultRepository results = mock(RuntimeExecutionResultRepository.class);
        RuntimeResultWaitRegistry waits = registry();
        RuntimeResultPollingService service = new RuntimeResultPollingService(results, waits, properties());
        var delivered = new ArrayList<String>();
        var reservation = waits.reserve(
                        RuntimeResultWaitMode.SEGMENT_EVENTS, (events, terminal) -> accept(delivered, events), () -> {})
                .orElseThrow();
        reservation.bind(target(), 10L);
        when(results.readSegmentEvents(target(), 10L, 200))
                .thenReturn(Optional.of(batch(List.of(event("agent", 11L)), false)));
        when(results.readSegmentEvents(target(), 11L, 200))
                .thenReturn(Optional.of(batch(List.of(event("idle", 12L)), true)));
        try {
            service.poll();
            service.poll();
        } finally {
            service.close();
        }

        verify(results).readSegmentEvents(target(), 10L, 200);
        verify(results).readSegmentEvents(target(), 11L, 200);
        assertThat(delivered).containsExactly("agent", "idle");
        assertThat(waits.registeredResponses()).isZero();
    }

    @Test
    void shouldUseSubmittedEventNotificationAsAsynchronousFastPath() throws Exception {
        RuntimeExecutionResultRepository results = mock(RuntimeExecutionResultRepository.class);
        RuntimeResultWaitRegistry waits = registry();
        RuntimeResultPollingService service = new RuntimeResultPollingService(results, waits, properties());
        var delivered = new CountDownLatch(1);
        var reservation = waits.reserve(
                        RuntimeResultWaitMode.EXECUTION_TERMINAL,
                        (events, terminal) -> {
                            delivered.countDown();
                            return true;
                        },
                        () -> {})
                .orElseThrow();
        reservation.bind(target(), 10L);
        when(results.findExecutionTerminal(target())).thenReturn(Optional.of(event("idle", 12L)));
        try {
            service.notifyCommitted(target());
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            service.close();
        }
        assertThat(waits.registeredResponses()).isZero();
    }

    private static void reserveTerminal(RuntimeResultWaitRegistry waits, List<String> delivered) {
        var reservation = waits.reserve(
                        RuntimeResultWaitMode.EXECUTION_TERMINAL,
                        (events, terminal) -> accept(delivered, events),
                        () -> {})
                .orElseThrow();
        assertThat(reservation.bind(target(), 10L)).isTrue();
    }

    private static boolean accept(List<String> delivered, List<CommittedEventDTO> events) {
        delivered.addAll(events.stream().map(CommittedEventDTO::getEventId).toList());
        return true;
    }

    private static RuntimeResultWaitRegistry registry() {
        return new RuntimeResultWaitRegistry(properties());
    }

    private static RuntimeEventProperties properties() {
        var properties = new RuntimeEventProperties();
        properties.setResultWaitMaxResponses(8);
        properties.setResultWaitTimeout(Duration.ofMinutes(1));
        properties.setResultPollBatchSize(8);
        properties.setResultReadLimit(200);
        return properties;
    }

    private static ExecutionTargetDTO target() {
        return new ExecutionTargetDTO("session", "execution", "root-event", "segment");
    }

    private static SegmentEventBatchDTO batch(List<CommittedEventDTO> events, boolean terminal) {
        var batch = new SegmentEventBatchDTO();
        batch.setEvents(events);
        batch.setTerminal(terminal);
        return batch;
    }

    private static CommittedEventDTO event(String eventId, long eventSeq) {
        var event = new CommittedEventDTO();
        event.setEventId(eventId);
        event.setEventSeq(eventSeq);
        return event;
    }
}
