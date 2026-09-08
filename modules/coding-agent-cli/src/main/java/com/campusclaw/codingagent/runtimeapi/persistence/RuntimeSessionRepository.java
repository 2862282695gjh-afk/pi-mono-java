/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;

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

    UserEventAcceptance acceptUserEvent(
            String sessionId, RuntimeEntryDTO entry, CommittedEventDTO event, OffsetDateTime acceptedAt);

    /**
     * 行锁内观察当前 Session 与完整分支，不修改持久化状态，也不准备 Agent。
     *
     * @param sessionId Session 标识
     * @return 缺失时为空；忙状态只返回 Session，不查询历史
     */
    Optional<RuntimeCompactionSnapshotDTO> observeCompaction(String sessionId);

    /**
     * 在行锁内复核 idle、当前叶节点及模型配置；保留并发名称修改，不追加 Entry。
     * 调用方须先确认观察历史有可恢复上下文并准备执行资源；本端口不执行压缩或分配容量。
     *
     * @param observed 准备压缩前在行锁内观察的 Session，调用方不得修改
     * @param acceptedAt 接受时间
     * @return 准入状态，历史或配置已改变时返回 BUSY
     */
    CompactionAcceptanceStatus acceptCompaction(RuntimeSessionDTO observed, OffsetDateTime acceptedAt);

    RuntimeEntryDTO appendEntry(RuntimeEntryDTO entry);

    RuntimeEntryDTO appendEntry(RuntimeEntryDTO entry, List<CommittedEventDTO> events);

    RuntimeEntryDTO appendEntryWithUsage(RuntimeEntryDTO entry, RuntimeRecordDTO record, Usage usage);

    RuntimeEntryDTO appendEntryWithUsage(
            RuntimeEntryDTO entry, RuntimeRecordDTO record, Usage usage, List<CommittedEventDTO> events);

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

    /**
     * 在行锁内复核当前模型能力，同值不生成事件或推进版本。
     *
     * @param sessionId Session 标识
     * @param expectedVersion 条件更新版本；null 表示使用锁内当前值
     * @param thinking 目标开关
     * @param admission 只进行本地能力检查，不得刷新目录、远端调用或修改 Session
     * @param entryFactory 基于锁内旧值生成事件，不得修改 Session 或执行远端调用
     * @param updatedAt 更新时间
     * @return 更新状态、当前 Session 和本次事件序号
     */
    SessionConfigurationUpdateDTO updateThinking(
            String sessionId,
            Long expectedVersion,
            boolean thinking,
            Consumer<RuntimeSessionDTO> admission,
            Function<RuntimeSessionDTO, RuntimeEntryDTO> entryFactory,
            OffsetDateTime updatedAt);

    SessionDeletionStatus beginDeletion(String sessionId, OffsetDateTime deletedAt);

    Optional<String> claimCleanupTask(OffsetDateTime now, OffsetDateTime staleBefore);

    void completeCleanup(String sessionId);

    void retryCleanup(String sessionId, OffsetDateTime now, OffsetDateTime nextAttemptAt, String lastError);
}
