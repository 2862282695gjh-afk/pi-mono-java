/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 当前分支 Agent 历史分批恢复测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventQueryServiceTest {
    private static final String SESSION_ID = "session_query";

    private RuntimeSessionRepository repository;

    private RuntimeEntryCodec codec;

    private RuntimeEventQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        codec = mock(RuntimeEntryCodec.class);
        service = new RuntimeEventQueryService(repository, codec);
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
        when(repository.listCurrentBranchEntries(SESSION_ID, 0L, 500)).thenReturn(firstBatch);
        when(repository.listCurrentBranchEntries(SESSION_ID, 500L, 500)).thenReturn(List.of(last));
        when(codec.toAgentMessages(any(), any())).thenReturn(restored);

        assertThat(service.restoreHistory(SESSION_ID, model)).isSameAs(restored);

        verify(repository).listCurrentBranchEntries(SESSION_ID, 500L, 500);
        verify(codec)
                .toAgentMessages(
                        org.mockito.ArgumentMatchers.<List<RuntimeEntryDTO>>argThat(entries -> entries.size() == 501),
                        any());
    }

    private static RuntimeEntryDTO entry(long sequence) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setEntrySeq(sequence);
        return entry;
    }
}
