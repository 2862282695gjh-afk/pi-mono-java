/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Session 单条时间线 Agent 历史分批恢复测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeContextHistoryServiceTest {
    private static final String SESSION_ID = "session_query";

    private RuntimeSessionRepository repository;

    private RuntimeEntryCodec codec;

    private RuntimeContextHistoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        codec = mock(RuntimeEntryCodec.class);
        service = new RuntimeContextHistoryService(repository, codec);
    }

    @Test
    void restoresMoreThanOneRepositoryBatchBeforeDecodingMessages() {
        List<RuntimeEntryDTO> firstBatch = new ArrayList<>();
        for (long sequence = 1L; sequence <= 500L; sequence++) {
            firstBatch.add(entry(sequence));
        }
        RuntimeEntryDTO last = entry(501L);
        Model model = mock(Model.class);
        List<Message> restored = List.of(new UserMessage("restored", 0L));
        when(repository.findTimelineUpperBound(SESSION_ID)).thenReturn(501L);
        when(repository.listTimelineEntries(SESSION_ID, 0L, 501L, 500)).thenReturn(firstBatch);
        when(repository.listTimelineEntries(SESSION_ID, 500L, 501L, 500)).thenReturn(List.of(last));
        when(codec.toAgentMessages(any(), any())).thenReturn(restored);

        assertThat(service.restoreHistory(SESSION_ID, model)).isSameAs(restored);

        verify(repository).listTimelineEntries(SESSION_ID, 500L, 501L, 500);
        verify(codec)
                .toAgentMessages(
                        org.mockito.ArgumentMatchers.<List<RuntimeEntryDTO>>argThat(entries -> entries.size() == 501),
                        any());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 1000, 10000})
    void restoresLongTimelineWithinOneCapturedUpperBound(int entryCount) {
        List<RuntimeEntryDTO> timeline = LongStream.rangeClosed(1L, entryCount)
                .mapToObj(RuntimeContextHistoryServiceTest::entry)
                .toList();
        Model model = mock(Model.class);
        List<Message> restored = List.of(new UserMessage("restored", 0L));
        when(repository.findTimelineUpperBound(SESSION_ID)).thenReturn((long) entryCount);
        when(repository.listTimelineEntries(eq(SESSION_ID), anyLong(), eq((long) entryCount), eq(500)))
                .thenAnswer(call -> page(timeline, call.getArgument(1), call.getArgument(3)));
        when(codec.toAgentMessages(any(), any())).thenReturn(restored);

        assertThat(service.restoreHistory(SESSION_ID, model)).isSameAs(restored);

        verify(repository).findTimelineUpperBound(SESSION_ID);
        verify(codec)
                .toAgentMessages(
                        org.mockito.ArgumentMatchers.<List<RuntimeEntryDTO>>argThat(
                                entries -> entries.size() == entryCount),
                        any());
    }

    private static List<RuntimeEntryDTO> page(List<RuntimeEntryDTO> timeline, long afterSeq, int limit) {
        return timeline.stream()
                .filter(entry -> entry.getEntrySeq() > afterSeq)
                .limit(limit)
                .toList();
    }

    private static RuntimeEntryDTO entry(long sequence) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setEntrySeq(sequence);
        return entry;
    }
}
