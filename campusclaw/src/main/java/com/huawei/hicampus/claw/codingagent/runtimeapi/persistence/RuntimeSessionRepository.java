/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;

/**
 * Runtime Session 持久化的事务边界端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeSessionRepository {
    void create(RuntimeSessionDTO session);

    Optional<RuntimeSessionDTO> find(String sessionId);

    Optional<SessionNameUpdateDTO> updateName(String sessionId, String displayName, OffsetDateTime updatedAt);

    UserEventAcceptance acceptUserEvent(String sessionId, RuntimeEntryDTO entry, OffsetDateTime acceptedAt);

    RuntimeEntryDTO appendEntry(RuntimeEntryDTO entry);

    RuntimeEntryDTO appendEntryWithUsage(RuntimeEntryDTO entry, RuntimeRecordDTO record, Usage usage);

    void finishExecution(String sessionId, OffsetDateTime finishedAt);

    List<RuntimeEntryDTO> listCurrentBranch(String sessionId, long afterSeq, int limit, boolean includeThinking);

    List<RuntimeEntryDTO> listCurrentBranchEntries(String sessionId, long afterSeq, int limit);

    /**
     * 在行锁内复核版本和状态，再调用只生成领域 Entry 的本地函数。
     *
     * @param sessionId Session 标识
     * @param expectedVersion 条件更新版本；null 表示使用锁内当前值
     * @param modelId 目标模型
     * @param modelSupportsThinking 目标模型能力
     * @param entriesFactory 基于锁内旧值生成事件，不得执行远端调用或修改 Session
     * @param updatedAt 更新时间
     * @return 更新状态、当前 Session 和本次最后事件序号
     */
    SessionConfigurationUpdateDTO updateModel(
            String sessionId,
            Long expectedVersion,
            String modelId,
            boolean modelSupportsThinking,
            Function<RuntimeSessionDTO, List<RuntimeEntryDTO>> entriesFactory,
            OffsetDateTime updatedAt);

    SessionConfigurationUpdateDTO updateThinking(
            String sessionId, long expectedVersion, boolean thinking, RuntimeEntryDTO entry, OffsetDateTime updatedAt);

    SessionDeletionStatus beginDeletion(String sessionId, OffsetDateTime deletedAt);

    Optional<String> claimCleanupTask(OffsetDateTime now, OffsetDateTime staleBefore);

    void completeCleanup(String sessionId);

    void retryCleanup(String sessionId, OffsetDateTime now, OffsetDateTime nextAttemptAt, String lastError);
}
