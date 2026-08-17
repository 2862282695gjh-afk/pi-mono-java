/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 MyBatis 和 openGauss 事务实现的 Runtime Session 仓库。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Repository
public class MyBatisRuntimeSessionRepository implements RuntimeSessionRepository {
    private final RuntimeSessionMapper mapper;

    public MyBatisRuntimeSessionRepository(RuntimeSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void create(RuntimeSessionDTO session) {
        if (mapper.tombstoneExists(session.getId()) > 0) {
            throw new IllegalStateException("session id is permanently reserved");
        }
        mapper.insertSession(session);
        mapper.insertSequence(session.getId());
        mapper.insertMaterialized(session.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeSessionDTO> find(String sessionId) {
        return Optional.ofNullable(mapper.findSession(sessionId));
    }

    @Override
    @Transactional
    public boolean beginDeletion(String sessionId, OffsetDateTime deletedAt) {
        if (mapper.lockSession(sessionId) == null) {
            return false;
        }
        mapper.insertTombstone(sessionId, deletedAt);
        mapper.insertCleanupTask(sessionId, deletedAt);
        mapper.deleteSession(sessionId);
        return true;
    }

    @Override
    @Transactional
    public Optional<String> claimCleanupTask(OffsetDateTime now, OffsetDateTime staleBefore) {
        String sessionId = mapper.lockNextCleanupTask(now, staleBefore);
        if (sessionId == null) {
            return Optional.empty();
        }
        mapper.markCleanupRunning(sessionId, now);
        return Optional.of(sessionId);
    }

    @Override
    @Transactional
    public void completeCleanup(String sessionId) {
        mapper.deleteEntries(sessionId);
        mapper.deleteSequence(sessionId);
        mapper.deleteMaterialized(sessionId);
        mapper.deleteCleanupTask(sessionId);
    }

    @Override
    @Transactional
    public void retryCleanup(String sessionId, OffsetDateTime now, OffsetDateTime nextAttemptAt, String lastError) {
        mapper.markCleanupRetry(sessionId, now, nextAttemptAt, lastError);
    }
}
