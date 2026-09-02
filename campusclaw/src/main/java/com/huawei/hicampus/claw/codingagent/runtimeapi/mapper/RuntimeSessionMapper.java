/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Runtime Session 主记录、tombstone 与清理任务的数据库 Mapper。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Mapper
public interface RuntimeSessionMapper {
    int insertSession(RuntimeSessionDTO session);

    int insertSequence(@Param("sessionId") String sessionId);

    int insertMaterialized(@Param("sessionId") String sessionId, @Param("payload") String payload);

    int insertStats(@Param("sessionId") String sessionId);

    RuntimeSessionDTO findSession(@Param("sessionId") String sessionId);

    RuntimeSessionDTO lockSessionForUpdate(@Param("sessionId") String sessionId);

    Long lockNextSequence(@Param("sessionId") String sessionId);

    int incrementSequence(@Param("sessionId") String sessionId);

    int insertEntry(RuntimeEntryDTO entry);

    int insertRecord(RuntimeRecordDTO record);

    int incrementMessageCount(@Param("sessionId") String sessionId);

    int accumulateUsageStats(
            @Param("sessionId") String sessionId,
            @Param("cachedTokens") long cachedTokens,
            @Param("uncachedTokens") long uncachedTokens,
            @Param("totalTokens") long totalTokens,
            @Param("costTotal") double costTotal);

    int markSessionRunning(
            @Param("sessionId") String sessionId,
            @Param("activeLeafId") String activeLeafId,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int updateActiveLeaf(@Param("sessionId") String sessionId, @Param("activeLeafId") String activeLeafId);

    int updateActiveLeafAnyState(@Param("sessionId") String sessionId, @Param("activeLeafId") String activeLeafId);

    int markSessionIdle(@Param("sessionId") String sessionId, @Param("updatedAt") OffsetDateTime updatedAt);

    List<RuntimeEntryDTO> listCurrentBranch(
            @Param("sessionId") String sessionId,
            @Param("afterSeq") long afterSeq,
            @Param("limit") int limit,
            @Param("includeThinking") boolean includeThinking);

    List<RuntimeEntryDTO> listCurrentBranchEntries(
            @Param("sessionId") String sessionId, @Param("afterSeq") long afterSeq, @Param("limit") int limit);

    int updateSessionModel(
            @Param("sessionId") String sessionId,
            @Param("modelId") String modelId,
            @Param("thinking") boolean thinking,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int updateSessionThinking(
            @Param("sessionId") String sessionId,
            @Param("thinking") boolean thinking,
            @Param("updatedAt") OffsetDateTime updatedAt);

    int insertTombstone(@Param("sessionId") String sessionId, @Param("deletedAt") OffsetDateTime deletedAt);

    int insertCleanupTask(@Param("sessionId") String sessionId, @Param("createdAt") OffsetDateTime createdAt);

    int deleteSession(@Param("sessionId") String sessionId);

    int tombstoneExists(@Param("sessionId") String sessionId);

    String lockNextCleanupTask(@Param("now") OffsetDateTime now, @Param("staleBefore") OffsetDateTime staleBefore);

    int markCleanupRunning(@Param("sessionId") String sessionId, @Param("updatedAt") OffsetDateTime updatedAt);

    int deleteEntries(@Param("sessionId") String sessionId);

    int deleteRecords(@Param("sessionId") String sessionId);

    int deleteStats(@Param("sessionId") String sessionId);

    int deleteSequence(@Param("sessionId") String sessionId);

    int deleteMaterialized(@Param("sessionId") String sessionId);

    int deleteCleanupTask(@Param("sessionId") String sessionId);

    int markCleanupRetry(
            @Param("sessionId") String sessionId,
            @Param("updatedAt") OffsetDateTime updatedAt,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
            @Param("lastError") String lastError);
}
