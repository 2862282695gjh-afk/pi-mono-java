/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * Runtime Session 持久化的事务边界端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeSessionRepository {
    void create(RuntimeSessionDTO session);

    Optional<RuntimeSessionDTO> find(String sessionId);

    UserEventAcceptance acceptUserEvent(String sessionId, RuntimeEntryDTO entry, OffsetDateTime acceptedAt);

    RuntimeEntryDTO appendEntry(RuntimeEntryDTO entry);

    RuntimeEntryDTO appendEntryWithUsage(RuntimeEntryDTO entry, RuntimeRecordDTO record, Usage usage);

    void finishExecution(String sessionId, OffsetDateTime finishedAt);

    List<RuntimeEntryDTO> listCurrentBranch(String sessionId, long afterSeq, int limit, boolean includeThinking);

    List<RuntimeEntryDTO> listCurrentBranchEntries(String sessionId, long afterSeq, int limit);

    SessionConfigurationUpdate updateModel(
            String sessionId,
            long expectedVersion,
            String modelId,
            boolean modelSupportsThinking,
            List<RuntimeEntryDTO> entries,
            OffsetDateTime updatedAt);

    SessionConfigurationUpdate updateThinking(
            String sessionId, long expectedVersion, boolean thinking, RuntimeEntryDTO entry, OffsetDateTime updatedAt);

    SessionDeletionStatus beginDeletion(String sessionId, OffsetDateTime deletedAt);

    Optional<String> claimCleanupTask(OffsetDateTime now, OffsetDateTime staleBefore);

    void completeCleanup(String sessionId);

    void retryCleanup(String sessionId, OffsetDateTime now, OffsetDateTime nextAttemptAt, String lastError);
}
