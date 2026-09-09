/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.LongStream;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证压缩持久化端口的分支、分页和受影响行检查；真实事务语义由 openGauss 测试覆盖。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class MyBatisRuntimeCompactionRepositoryTest {
    private final RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);

    private final RuntimeSessionRepository repository = new MyBatisRuntimeSessionRepository(mapper);

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    @BeforeEach
    void supplyUsageSnapshot() {
        when(mapper.findLifetimeUsage("session")).thenReturn(new RuntimeLifetimeUsageDTO());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "running"})
    void shouldAvoidHistoryReadsForMissingOrBusySession(String state) {
        if (state.equals("running")) {
            var session = session();
            session.setState(state);
            when(mapper.lockSessionForUpdate("session")).thenReturn(session);
        }
        var observed = repository.observeCompaction("session");
        assertThat(observed.isEmpty()).isEqualTo(state.equals("missing"));
        observed.ifPresent(snapshot -> assertThat(snapshot.entries()).isEmpty());
        verify(mapper).lockSessionForUpdate("session");
        if (state.equals("running")) {
            verify(mapper).findLifetimeUsage("session");
        }
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void shouldReadAllPagesInOrderAndReturnAnUnmodifiableList() {
        when(mapper.lockSessionForUpdate("session")).thenReturn(session());
        List<RuntimeEntryDTO> first = LongStream.rangeClosed(1, 500)
                .mapToObj(sequence -> {
                    var entry = new RuntimeEntryDTO();
                    entry.setEntrySeq(sequence);
                    return entry;
                })
                .toList();
        var last = new RuntimeEntryDTO();
        last.setEntrySeq(503L);
        when(mapper.listCurrentBranchEntries("session", 0L, 500)).thenReturn(first);
        when(mapper.listCurrentBranchEntries("session", 500L, 500)).thenReturn(List.of(last));
        var observed = repository.observeCompaction("session").orElseThrow();
        assertThat(observed.entries()).hasSize(501).endsWith(last);
        assertThat(observed.entries()).extracting(RuntimeEntryDTO::getEntrySeq).isSorted();
        assertThatThrownBy(() -> observed.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(mapper).lockSessionForUpdate("session");
        verify(mapper).listCurrentBranchEntries("session", 0L, 500);
        verify(mapper).listCurrentBranchEntries("session", 500L, 500);
        verify(mapper).findLifetimeUsage("session");
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "running", "leaf", "model", "thinking"})
    void shouldRejectChangedContextWithoutAnyWrite(String change) {
        var current = session();
        switch (change) {
            case "missing" -> current = null;
            case "running" -> current.setState("running");
            case "leaf" -> current.setActiveLeafId("other");
            case "model" -> current.setModelId("other");
            case "thinking" -> current.setThinking(true);
            default -> throw new AssertionError(change);
        }
        when(mapper.lockSessionForUpdate("session")).thenReturn(current);
        assertThat(repository.acceptCompaction(session(), now))
                .isEqualTo(
                        change.equals("missing")
                                ? CompactionAcceptanceStatus.NOT_FOUND
                                : CompactionAcceptanceStatus.BUSY);
        verify(mapper).lockSessionForUpdate("session");
        if (!change.equals("missing")) {
            verify(mapper).findLifetimeUsage("session");
        }
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldUseCurrentLeafAndRequireOneStateUpdateWithoutAppendingEntries(int affected) {
        var current = session();
        current.setDisplayName("名称已改");
        current.setResourceVersion(12L);
        when(mapper.lockSessionForUpdate("session")).thenReturn(current);
        when(mapper.markSessionRunning("session", "leaf", now)).thenReturn(affected);
        if (affected == 1) {
            assertThat(repository.acceptCompaction(session(), now)).isEqualTo(CompactionAcceptanceStatus.ACCEPTED);
        } else {
            assertThatThrownBy(() -> repository.acceptCompaction(session(), now))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("session did not enter running state");
        }
        verify(mapper).lockSessionForUpdate("session");
        verify(mapper).markSessionRunning("session", "leaf", now);
        verify(mapper).findLifetimeUsage("session");
        verify(mapper, never()).insertEntry(any());
        verify(mapper, never()).insertRecord(any());
        verifyNoMoreInteractions(mapper);
    }

    private static RuntimeSessionDTO session() {
        var session = new RuntimeSessionDTO();
        session.setId("session");
        session.setModelId("model");
        session.setState("idle");
        session.setActiveLeafId("leaf");
        session.setResourceVersion(1L);
        return session;
    }
}
