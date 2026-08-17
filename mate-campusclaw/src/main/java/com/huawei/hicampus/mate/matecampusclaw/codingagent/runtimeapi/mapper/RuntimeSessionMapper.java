/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.mapper;

import java.time.OffsetDateTime;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Runtime Session 主记录、tombstone 与清理任务的数据库 Mapper。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Mapper
public interface RuntimeSessionMapper {
    int insertSession(RuntimeSessionDTO session);

    int insertSequence(@Param("sessionId") String sessionId);

    int insertMaterialized(@Param("sessionId") String sessionId);

    RuntimeSessionDTO findSession(@Param("sessionId") String sessionId);

    Integer lockSession(@Param("sessionId") String sessionId);

    int insertTombstone(@Param("sessionId") String sessionId, @Param("deletedAt") OffsetDateTime deletedAt);

    int insertCleanupTask(@Param("sessionId") String sessionId, @Param("createdAt") OffsetDateTime createdAt);

    int deleteSession(@Param("sessionId") String sessionId);

    int tombstoneExists(@Param("sessionId") String sessionId);

    String lockNextCleanupTask(@Param("now") OffsetDateTime now, @Param("staleBefore") OffsetDateTime staleBefore);

    int markCleanupRunning(@Param("sessionId") String sessionId, @Param("updatedAt") OffsetDateTime updatedAt);

    int deleteEntries(@Param("sessionId") String sessionId);

    int deleteSequence(@Param("sessionId") String sessionId);

    int deleteMaterialized(@Param("sessionId") String sessionId);

    int deleteCleanupTask(@Param("sessionId") String sessionId);

    int markCleanupRetry(
            @Param("sessionId") String sessionId,
            @Param("updatedAt") OffsetDateTime updatedAt,
            @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
            @Param("lastError") String lastError);
}
