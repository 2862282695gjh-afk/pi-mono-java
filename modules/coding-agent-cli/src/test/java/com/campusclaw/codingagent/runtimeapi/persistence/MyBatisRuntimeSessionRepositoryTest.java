/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;

import org.junit.jupiter.api.Test;

/**
 * Runtime Entry、Usage Record 与 Stats 原子写入规则测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
class MyBatisRuntimeSessionRepositoryTest {
    @Test
    void appendsEntryAndUsageRecordWithSharedSequenceAndPiStatsRules() {
        RuntimeSessionMapper mapper = successfulMapper();
        RuntimeSessionDTO session = session();
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        when(mapper.lockNextSequence("session")).thenReturn(2L, 3L);
        RuntimeEntryDTO entry = entry();
        RuntimeRecordDTO record = record();
        Usage usage = new Usage(10, 5, 2, 1, 18, new Cost(0.1, 0.2, 0.01, 0.02, 0.33));

        new MyBatisRuntimeSessionRepository(mapper).appendEntryWithUsage(entry, record, usage);

        assertThat(entry.getEntrySeq()).isEqualTo(2L);
        assertThat(record.getRecordSeq()).isEqualTo(3L);
        verify(mapper).updateActiveLeaf("session", "assistant");
        verify(mapper).incrementMessageCount("session");
        verify(mapper).accumulateUsageStats("session", 2L, 11L, 18L, 0.33);
        verify(mapper, times(2)).incrementSequence("session");
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
        when(mapper.insertEntry(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.insertRecord(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.incrementSequence("session")).thenReturn(1);
        when(mapper.updateActiveLeaf("session", "assistant")).thenReturn(1);
        when(mapper.incrementMessageCount("session")).thenReturn(1);
        when(mapper.accumulateUsageStats("session", 2L, 11L, 18L, 0.33)).thenReturn(1);
        return mapper;
    }

    @Test
    void shouldUpdateRunningNameOnlyAfterLockingWithoutWritingHistory() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        RuntimeSessionDTO session = session();
        session.setState("running");
        OffsetDateTime now = OffsetDateTime.parse("2026-09-05T00:00:00Z");
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        when(mapper.updateSessionName("session", "next", now)).thenReturn(1);
        assertThat(new MyBatisRuntimeSessionRepository(mapper).updateName("session", "next", now))
                .contains(new SessionNameUpdateDTO("next", true));
        var order = inOrder(mapper);
        order.verify(mapper).lockSessionForUpdate("session");
        order.verify(mapper).updateSessionName("session", "next", now);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void shouldLeaveIdenticalNameVersionTimestampAndHistoryUntouched() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        RuntimeSessionDTO session = session();
        session.setDisplayName("same");
        when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        assertThat(new MyBatisRuntimeSessionRepository(mapper)
                        .updateName("session", "same", OffsetDateTime.parse("2026-09-05T00:00:00Z")))
                .contains(new SessionNameUpdateDTO("same", false));
        verify(mapper).lockSessionForUpdate("session");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void shouldDetectDeletionAndFailedAffectedRowCount() {
        RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);
        var repository = new MyBatisRuntimeSessionRepository(mapper);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-05T00:00:00Z");
        assertThat(repository.updateName("missing", "next", now)).isEmpty();
        when(mapper.lockSessionForUpdate("session")).thenReturn(session());
        assertThatThrownBy(() -> repository.updateName("session", "next", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session name was not updated");
        verify(mapper).lockSessionForUpdate("missing");
        verify(mapper).lockSessionForUpdate("session");
        verify(mapper).updateSessionName("session", "next", now);
        verifyNoMoreInteractions(mapper);
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session");
        session.setActiveLeafId("user");
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
}
