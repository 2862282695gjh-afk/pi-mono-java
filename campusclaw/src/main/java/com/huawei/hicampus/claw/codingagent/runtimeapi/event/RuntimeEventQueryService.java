/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.springframework.stereotype.Service;

/**
 * 把当前 Session 分支的内部 Entry 恢复为 Agent 历史。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeEventQueryService {
    private static final int RESTORE_BATCH_SIZE = 500;

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    public RuntimeEventQueryService(RuntimeSessionRepository repository, RuntimeEntryCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    public List<Message> restoreHistory(String sessionId, Model model) {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        long afterSeq = 0L;
        while (true) {
            List<RuntimeEntryDTO> batch = repository.listCurrentBranchEntries(sessionId, afterSeq, RESTORE_BATCH_SIZE);
            entries.addAll(batch);
            if (batch.size() < RESTORE_BATCH_SIZE) {
                break;
            }
            afterSeq = batch.getLast().getEntrySeq();
        }
        return codec.toAgentMessages(entries, model);
    }
}
