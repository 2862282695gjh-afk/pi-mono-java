/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Runtime Entry、Usage Record 与 Stats 原子写入规则测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
class MyBatisRuntimeSessionRepositoryTest {
    @Test
    void appendsEntryUsageAndCommittedEventWithSharedSequence() {
        RuntimeSessionMapper mapper = successfulMapper();
        RuntimeSessionDTO session = session();
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        when(mapper.lockNextSequence("session")).thenReturn(2L, 3L, 4L);
        RuntimeEntryDTO entry = entry();
        RuntimeRecordDTO record = record();
        CommittedEventDTO event = event("assistant");
        Usage usage = new Usage(10, 5, 2, 1, 18, new Cost(0.1, 0.2, 0.01, 0.02, 0.33));

        new MyBatisRuntimeSessionRepository(mapper).appendEntryWithUsage(entry, record, usage, List.of(event));

        assertThat(entry.getEntrySeq()).isEqualTo(2L);
        assertThat(record.getRecordSeq()).isEqualTo(3L);
        assertThat(event.getEventSeq()).isEqualTo(4L);
        verify(mapper).updateActiveLeaf("session", "assistant");
        verify(mapper).incrementMessageCount("session");
        var delta = ArgumentCaptor.forClass(RuntimeLifetimeUsageDTO.class);
        verify(mapper).accumulateUsageStats(eq("session"), delta.capture());
        assertThat(delta.getValue())
                .extracting("input", "output", "cacheRead", "cacheWrite", "totalTokens")
                .containsExactly(10L, 5L, 2L, 1L, 18L);
        assertThat(delta.getValue().getCostTotal()).isEqualByComparingTo("0.33");
        verify(mapper).insertCommittedEvent(event);
        verify(mapper).insertCommittedEventProjection("session", "assistant", 1, "runtime");
        verify(mapper, times(3)).incrementSequence("session");
    }

    @Test
    void shouldAcceptUserEntryAndCommittedEventTogether() {
        RuntimeSessionMapper mapper = successfulMapper();
        RuntimeSessionDTO session = session();
        RuntimeEntryDTO entry = entry();
        entry.setId("next-user");
        entry.setType("user.message");
        CommittedEventDTO event = event("next-user");
        OffsetDateTime acceptedAt = OffsetDateTime.parse("2026-09-08T01:00:00Z");
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        when(mapper.lockNextSequence("session")).thenReturn(5L, 6L);
        when(mapper.markSessionRunning("session", "next-user", acceptedAt)).thenReturn(1);

        UserEventAcceptance result =
                new MyBatisRuntimeSessionRepository(mapper).acceptUserEvent("session", entry, event, acceptedAt);

        assertThat(result.status()).isEqualTo(UserEventAcceptance.Status.ACCEPTED);
        assertThat(entry.getEntrySeq()).isEqualTo(5L);
        assertThat(event.getEventSeq()).isEqualTo(6L);
        verify(mapper).insertCommittedEvent(event);
        verify(mapper).insertCommittedEventProjection("session", "next-user", 1, "runtime");
    }

    @Test
    void createsIndependentStatsWithoutLifetimeUsageMaterialization() {
        RuntimeSessionMapper mapper = successfulMapper();
        RuntimeSessionDTO session = session();

        new MyBatisRuntimeSessionRepository(mapper).create(session);

        verify(mapper).insertSequence("session");
        verify(mapper).insertMaterialized("session", "{}");
        verify(mapper).insertStats("session");
    }

    private static RuntimeSessionMapper successfulMapper() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        when(mapper.findLifetimeUsage("session")).thenReturn(new RuntimeLifetimeUsageDTO());
        when(mapper.insertEntry(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.insertRecord(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.insertCommittedEvent(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.insertCommittedEventProjection(any(), any(), anyInt(), any()))
                .thenReturn(1);
        when(mapper.incrementSequence("session")).thenReturn(1);
        when(mapper.updateActiveLeaf("session", "assistant")).thenReturn(1);
        when(mapper.incrementMessageCount("session")).thenReturn(1);
        when(mapper.accumulateUsageStats(eq("session"), any())).thenReturn(1);
        return mapper;
    }

    @Test
    void shouldUpdateRunningNameOnlyAfterLockingWithoutWritingHistory() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        when(mapper.findLifetimeUsage("session")).thenReturn(new RuntimeLifetimeUsageDTO());
        RuntimeSessionDTO session = session();
        session.setState("running");
        OffsetDateTime now = OffsetDateTime.parse("2026-09-05T00:00:00Z");
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        when(mapper.updateSessionName("session", "next", now)).thenReturn(1);
        assertThat(new MyBatisRuntimeSessionRepository(mapper).updateName("session", "next", now))
                .contains(new SessionNameUpdateDTO(session, true));
        assertThat(session.getDisplayName()).isEqualTo("next");
        assertThat(session.getResourceVersion()).isEqualTo(1L);
        assertThat(session.getUpdatedAt()).isEqualTo(now);
        assertThat(session.getState()).isEqualTo("running");
        assertThat(session.getActiveLeafId()).isEqualTo("user");
        var order = inOrder(mapper);
        order.verify(mapper).lockSessionForUpdate("session");
        order.verify(mapper).findLifetimeUsage("session");
        order.verify(mapper).updateSessionName("session", "next", now);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void shouldLeaveIdenticalNameVersionTimestampAndHistoryUntouched() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        when(mapper.findLifetimeUsage("session")).thenReturn(new RuntimeLifetimeUsageDTO());
        RuntimeSessionDTO session = session();
        session.setDisplayName("same");
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        assertThat(new MyBatisRuntimeSessionRepository(mapper)
                        .updateName("session", "same", OffsetDateTime.parse("2026-09-05T00:00:00Z")))
                .contains(new SessionNameUpdateDTO(session, false));
        assertThat(session.getResourceVersion()).isZero();
        assertThat(session.getUpdatedAt()).isNull();
        verify(mapper).lockSessionForUpdate("session");
        verify(mapper).findLifetimeUsage("session");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void shouldDetectDeletionAndFailedAffectedRowCount() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        when(mapper.findLifetimeUsage("session")).thenReturn(new RuntimeLifetimeUsageDTO());
        var repository = new MyBatisRuntimeSessionRepository(mapper);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-05T00:00:00Z");
        assertThat(repository.updateName("missing", "next", now)).isEmpty();
        when(mapper.lockSessionForUpdate("session")).thenReturn(session());
        assertThatThrownBy(() -> repository.updateName("session", "next", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session name was not updated");
        verify(mapper).lockSessionForUpdate("missing");
        verify(mapper).lockSessionForUpdate("session");
        verify(mapper).findLifetimeUsage("session");
        verify(mapper).updateSessionName("session", "next", now);
        verifyNoMoreInteractions(mapper);
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session");
        session.setActiveLeafId("user");
        session.setState("idle");
        return session;
    }

    private static RuntimeEntryDTO entry() {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId("session");
        entry.setId("assistant");
        entry.setType("assistant.message.completed");
        entry.setTimestamp(OffsetDateTime.parse("2026-08-25T00:00:00Z"));
        entry.setPayload("{}");
        return entry;
    }

    private static RuntimeRecordDTO record() {
        RuntimeRecordDTO record = new RuntimeRecordDTO();
        record.setSessionId("session");
        record.setId("usage");
        record.setLane("main");
        record.setRunId("user");
        record.setType("usage");
        record.setTimestamp(OffsetDateTime.parse("2026-08-25T00:00:00Z"));
        record.setPayload("{}");
        return record;
    }

    private static CommittedEventDTO event(String anchorEntryId) {
        CommittedEventDTO event = new CommittedEventDTO();
        event.setSessionId("session");
        event.setEventId("event-" + anchorEntryId);
        event.setAnchorEntryId(anchorEntryId);
        event.setType("agent.message");
        event.setCreatedAt(OffsetDateTime.parse("2026-09-08T01:00:00Z"));
        event.setPayload("{}");
        return event;
    }
}
