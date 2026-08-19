/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 当前分支事件分页和 Agent 历史分批恢复测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeEventQueryServiceTest {
    private static final String SESSION_ID = "session_query";

    private RuntimeSessionRepository repository;

    private RuntimeEntryCodec codec;

    private RuntimeEventCursorCodec cursorCodec;

    private RuntimeEventQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        codec = mock(RuntimeEntryCodec.class);
        cursorCodec = mock(RuntimeEventCursorCodec.class);
        service = new RuntimeEventQueryService(repository, codec, cursorCodec);
    }

    @Test
    void returnsOpaqueCursorOnlyWhenAnotherEventExists() {
        RuntimeEntryDTO first = entry(1L);
        RuntimeEntryDTO second = entry(2L);
        RuntimeEntryDTO lookAhead = entry(3L);
        when(repository.find(SESSION_ID)).thenReturn(Optional.of(new RuntimeSessionDTO()));
        when(repository.listCurrentBranch(SESSION_ID, 7L, 3)).thenReturn(List.of(first, second, lookAhead));
        when(cursorCodec.decode("page_current", SESSION_ID)).thenReturn(7L);
        when(cursorCodec.encode(SESSION_ID, 2L)).thenReturn("page_next");
        when(codec.toHistoryEvent(first)).thenReturn(Map.of("entry_seq", 1L));
        when(codec.toHistoryEvent(second)).thenReturn(Map.of("entry_seq", 2L));

        var page = service.list(SESSION_ID, "2", "page_current");

        assertThat(page.getEvents()).containsExactly(Map.of("entry_seq", 1L), Map.of("entry_seq", 2L));
        assertThat(page.getNextPage()).isEqualTo("page_next");
        verify(codec, never()).toHistoryEvent(lookAhead);
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
        when(repository.listCurrentBranch(SESSION_ID, 0L, 500)).thenReturn(firstBatch);
        when(repository.listCurrentBranch(SESSION_ID, 500L, 500)).thenReturn(List.of(last));
        when(codec.toAgentMessages(any(), any())).thenReturn(restored);

        assertThat(service.restoreHistory(SESSION_ID, model)).isSameAs(restored);

        verify(repository).listCurrentBranch(SESSION_ID, 500L, 500);
        verify(codec)
                .toAgentMessages(
                        org.mockito.ArgumentMatchers.<List<RuntimeEntryDTO>>argThat(entries -> entries.size() == 501),
                        any());
    }

    @Test
    void rejectsInvalidLimitBeforeReadingSession() {
        assertThatThrownBy(() -> service.list(SESSION_ID, "201", null))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_EVENT_LIST_QUERY));

        verify(repository, never()).find(any());
    }

    private static RuntimeEntryDTO entry(long sequence) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setEntrySeq(sequence);
        return entry;
    }
}
