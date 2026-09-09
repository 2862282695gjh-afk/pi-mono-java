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

    List<RuntimeEntryDTO> listCurrentBranchEntries(String sessionId, long afterSeq, int limit);

    Optional<List<CommittedEventDTO>> findEventPage(String sessionId, long offset, int limit);

    /**
     * 在同一事务内更新模型，并为每个配置 Entry 保存完整公共事件。
     *
     * @param sessionId 会话 ID
     * @param expectedVersion 预期资源版本；空表示无条件更新
     * @param modelId 新模型 ID
     * @param modelSupportsThinking 新模型是否支持深度思考
     * @param entriesFactory 锁内配置 Entry 工厂
     * @param eventFactory 已定稿 Entry 对应的公共事件工厂，不得为空
     * @param updatedAt 更新时间
     * @return 配置更新结果
     */
    SessionConfigurationUpdateDTO updateModel(
            String sessionId,
            Long expectedVersion,
            String modelId,
            boolean modelSupportsThinking,
            Function<RuntimeSessionDTO, List<RuntimeEntryDTO>> entriesFactory,
            Function<RuntimeEntryDTO, CommittedEventDTO> eventFactory,
            OffsetDateTime updatedAt);

    /**
     * 在同一事务内更新深度思考配置，并保存完整公共事件。
     *
     * @param sessionId 会话 ID
     * @param expectedVersion 预期资源版本；空表示无条件更新
     * @param thinking 新深度思考开关
     * @param admission 锁内业务准入检查
     * @param entryFactory 锁内配置 Entry 工厂
     * @param eventFactory 已定稿 Entry 对应的公共事件工厂，不得为空
     * @param updatedAt 更新时间
     * @return 配置更新结果
     */
    SessionConfigurationUpdateDTO updateThinking(
            String sessionId,
            Long expectedVersion,
            boolean thinking,
            Consumer<RuntimeSessionDTO> admission,
            Function<RuntimeSessionDTO, RuntimeEntryDTO> entryFactory,
            Function<RuntimeEntryDTO, CommittedEventDTO> eventFactory,
            OffsetDateTime updatedAt);

    SessionDeletionStatus beginDeletion(String sessionId, OffsetDateTime deletedAt);

    Optional<String> claimCleanupTask(OffsetDateTime now, OffsetDateTime staleBefore);

    void completeCleanup(String sessionId);

    void retryCleanup(String sessionId, OffsetDateTime now, OffsetDateTime nextAttemptAt, String lastError);
}
