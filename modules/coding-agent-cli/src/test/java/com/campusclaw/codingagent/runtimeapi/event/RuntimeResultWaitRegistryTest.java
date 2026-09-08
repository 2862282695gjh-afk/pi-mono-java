/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;

import org.junit.jupiter.api.Test;

class RuntimeResultWaitRegistryTest {
    @Test
    void shouldBoundRealResponsesWhileDeduplicatingTheirQueryTarget() {
        RuntimeResultWaitRegistry registry = registry(2);
        var firstEvents = new ArrayList<String>();
        var secondEvents = new ArrayList<String>();
        var first = registry.reserve(
                        RuntimeResultWaitMode.SEGMENT_EVENTS,
                        (events, terminal) -> accept(firstEvents, events),
                        () -> {})
                .orElseThrow();
        var second = registry.reserve(
                        RuntimeResultWaitMode.SEGMENT_EVENTS,
                        (events, terminal) -> accept(secondEvents, events),
                        () -> {})
                .orElseThrow();
        assertThat(registry.reserve(RuntimeResultWaitMode.SEGMENT_EVENTS, (events, terminal) -> true, () -> {}))
                .isEmpty();
        assertThat(first.bind(target(), 10L)).isTrue();
        assertThat(second.bind(target(), 10L)).isTrue();

        var claimed = registry.claimTargets(10);
        assertThat(claimed).singleElement().extracting("afterSeq").isEqualTo(10L);
        registry.deliver(claimed.getFirst(), List.of(event("agent", 11L)), false);
        var duplicate = registry.claimTargets(10).getFirst();
        registry.deliver(duplicate, List.of(event("agent", 11L)), false);
        var terminal = registry.claimTargets(10).getFirst();
        registry.deliver(terminal, List.of(event("idle", 12L)), true);

        assertThat(firstEvents).containsExactly("agent", "idle");
        assertThat(secondEvents).containsExactly("agent", "idle");
        assertThat(registry.registeredResponses()).isZero();
    }

    @Test
    void shouldRemoveOnlyResponseThatCannotAcceptCommittedResult() {
        RuntimeResultWaitRegistry registry = registry(2);
        var accepted = new ArrayList<String>();
        var rejected = registry.reserve(RuntimeResultWaitMode.EXECUTION_TERMINAL, (events, terminal) -> false, () -> {})
                .orElseThrow();
        var retained = registry.reserve(
                        RuntimeResultWaitMode.EXECUTION_TERMINAL,
                        (events, terminal) -> accept(accepted, events),
                        () -> {})
                .orElseThrow();
        rejected.bind(target(), 0L);
        retained.bind(target(), 0L);

        var claimed = registry.claimTargets(1).getFirst();
        registry.deliver(claimed, List.of(event("agent", 1L)), false);

        assertThat(registry.registeredResponses()).isOne();
        assertThat(accepted).containsExactly("agent");
        retained.close();
        assertThat(registry.registeredResponses()).isZero();
    }

    @Test
    void shouldExpireReservedResponseWithoutCreatingTerminalEvent() {
        RuntimeResultWaitRegistry registry = registry(1);
        var expired = new AtomicInteger();
        registry.reserve(RuntimeResultWaitMode.EXECUTION_TERMINAL, (events, terminal) -> true, expired::incrementAndGet)
                .orElseThrow();

        registry.expireTimedOut(Long.MAX_VALUE);

        assertThat(expired).hasValue(1);
        assertThat(registry.registeredResponses()).isZero();
        assertThat(registry.claimTargets(1)).isEmpty();
    }

    @Test
    void shouldIgnoreLateResultFromReplacedWaitGroupClaim() {
        RuntimeResultWaitRegistry registry = registry(1);
        var first = registry.reserve(RuntimeResultWaitMode.SEGMENT_EVENTS, (events, terminal) -> true, () -> {})
                .orElseThrow();
        first.bind(target(), 100L);
        var staleClaim = registry.claimTargets(1).getFirst();
        first.close();
        var delivered = new ArrayList<String>();
        var replacement = registry.reserve(
                        RuntimeResultWaitMode.SEGMENT_EVENTS, (events, terminal) -> accept(delivered, events), () -> {})
                .orElseThrow();
        replacement.bind(target(), 10L);
        var currentClaim = registry.claimTargets(1).getFirst();

        registry.deliver(staleClaim, List.of(), true);
        registry.release(staleClaim);

        assertThat(registry.registeredResponses()).isOne();
        assertThat(registry.claimTargets(1)).isEmpty();
        registry.deliver(currentClaim, List.of(event("current", 11L)), true);
        assertThat(delivered).containsExactly("current");
    }

    private static boolean accept(List<String> delivered, List<CommittedEventDTO> events) {
        delivered.addAll(events.stream().map(CommittedEventDTO::getEventId).toList());
        return true;
    }

    private static RuntimeResultWaitRegistry registry(int maxResponses) {
        var properties = new RuntimeEventProperties();
        properties.setResultWaitMaxResponses(maxResponses);
        properties.setResultWaitTimeout(Duration.ofMinutes(1));
        return new RuntimeResultWaitRegistry(properties);
    }

    private static ExecutionTargetDTO target() {
        return new ExecutionTargetDTO("session", "execution", "root-event", "segment");
    }

    private static CommittedEventDTO event(String eventId, long eventSeq) {
        var event = new CommittedEventDTO();
        event.setEventId(eventId);
        event.setEventSeq(eventSeq);
        return event;
    }
}
