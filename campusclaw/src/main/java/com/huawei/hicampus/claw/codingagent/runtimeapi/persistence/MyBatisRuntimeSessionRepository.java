/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeCompactionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.UserEventAcceptance.Status;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 MyBatis 和 openGauss 事务实现的 Runtime Session 仓库。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Repository
public class MyBatisRuntimeSessionRepository implements RuntimeSessionRepository {
    private static final int COMPACTION_HISTORY_PAGE_SIZE = 500;

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
        mapper.insertMaterialized(session.getId(), "{}");
        mapper.insertStats(session.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeSessionDTO> find(String sessionId) {
        return Optional.ofNullable(mapper.findSession(sessionId));
    }

    @Override
    @Transactional
    public Optional<SessionNameUpdateDTO> updateName(String sessionId, String displayName, OffsetDateTime updatedAt) {
        RuntimeSessionDTO session = lockSession(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (Objects.equals(session.getDisplayName(), displayName)) {
            return Optional.of(new SessionNameUpdateDTO(session.getDisplayName(), false));
        }
        requireOne(mapper.updateSessionName(sessionId, displayName, updatedAt), "session name was not updated");
        return Optional.of(new SessionNameUpdateDTO(displayName, true));
    }

    @Override
    @Transactional
    public UserEventAcceptance acceptUserEvent(String sessionId, RuntimeEntryDTO entry, OffsetDateTime acceptedAt) {
        RuntimeSessionDTO session = lockSession(sessionId);
        if (session == null) {
            return new UserEventAcceptance(Status.NOT_FOUND, null);
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            return new UserEventAcceptance(Status.BUSY, session);
        }
        appendLocked(session, entry);
        incrementMessageCount(entry);
        requireOne(
                mapper.markSessionRunning(sessionId, entry.getId(), acceptedAt), "session did not enter running state");
        session.setState(RuntimeSessionState.RUNNING.value());
        session.setUpdatedAt(acceptedAt);
        session.setResourceVersion(session.getResourceVersion() + 1);
        session.setActiveLeafId(entry.getId());
        return new UserEventAcceptance(Status.ACCEPTED, session);
    }

    @Override
    @Transactional
    public Optional<RuntimeCompactionSnapshotDTO> observeCompaction(String sessionId) {
        RuntimeSessionDTO session = lockSession(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        if (RuntimeSessionState.IDLE.matches(session.getState())) {
            long afterSeq = 0L;
            List<RuntimeEntryDTO> page;
            do {
                page = mapper.listCurrentBranchEntries(sessionId, afterSeq, COMPACTION_HISTORY_PAGE_SIZE);
                entries.addAll(page);
                if (!page.isEmpty()) {
                    afterSeq = page.getLast().getEntrySeq();
                }
            } while (page.size() == COMPACTION_HISTORY_PAGE_SIZE);
        }
        return Optional.of(new RuntimeCompactionSnapshotDTO(session, List.copyOf(entries)));
    }

    @Override
    @Transactional
    public CompactionAcceptanceStatus acceptCompaction(RuntimeSessionDTO observed, OffsetDateTime acceptedAt) {
        RuntimeSessionDTO current = lockSession(observed.getId());
        if (current == null) {
            return CompactionAcceptanceStatus.NOT_FOUND;
        }
        if (!RuntimeSessionState.IDLE.matches(current.getState())
                || !Objects.equals(current.getActiveLeafId(), observed.getActiveLeafId())
                || !Objects.equals(current.getModelId(), observed.getModelId())
                || current.isThinking() != observed.isThinking()) {
            return CompactionAcceptanceStatus.BUSY;
        }
        requireOne(
                mapper.markSessionRunning(current.getId(), current.getActiveLeafId(), acceptedAt),
                "session did not enter running state");
        return CompactionAcceptanceStatus.ACCEPTED;
    }

    @Override
    @Transactional
    public RuntimeEntryDTO appendEntry(RuntimeEntryDTO entry) {
        RuntimeSessionDTO session = lockSession(entry.getSessionId());
        if (session == null) {
            throw new IllegalStateException("session disappeared during execution");
        }
        appendLocked(session, entry);
        requireOne(mapper.updateActiveLeaf(entry.getSessionId(), entry.getId()), "session active leaf was not updated");
        incrementMessageCount(entry);
        return entry;
    }

    @Override
    @Transactional
    public RuntimeEntryDTO appendEntryWithUsage(RuntimeEntryDTO entry, RuntimeRecordDTO record, Usage usage) {
        RuntimeSessionDTO session = lockSession(entry.getSessionId());
        if (session == null) {
            throw new IllegalStateException("session disappeared during execution");
        }
        appendLocked(session, entry);
        requireOne(mapper.updateActiveLeaf(entry.getSessionId(), entry.getId()), "session active leaf was not updated");
        incrementMessageCount(entry);
        appendRecordLocked(record);
        accumulateUsageStats(entry.getSessionId(), usage);
        return entry;
    }

    @Override
    @Transactional
    public void finishExecution(String sessionId, OffsetDateTime finishedAt) {
        requireOne(mapper.markSessionIdle(sessionId, finishedAt), "session did not return to idle state");
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuntimeEntryDTO> listCurrentBranch(
            String sessionId, long afterSeq, int limit, boolean includeThinking) {
        return mapper.listCurrentBranch(sessionId, afterSeq, limit, includeThinking);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuntimeEntryDTO> listCurrentBranchEntries(String sessionId, long afterSeq, int limit) {
        return mapper.listCurrentBranchEntries(sessionId, afterSeq, limit);
    }

    @Override
    @Transactional
    public SessionConfigurationUpdateDTO updateModel(
            String sessionId,
            Long expectedVersion,
            String modelId,
            boolean modelSupportsThinking,
            Function<RuntimeSessionDTO, List<RuntimeEntryDTO>> entriesFactory,
            OffsetDateTime updatedAt) {
        RuntimeSessionDTO session = lockSession(sessionId);
        SessionConfigurationUpdateDTO rejected = rejectConfigurationUpdate(session, expectedVersion);
        if (rejected != null || session.getModelId().equals(modelId)) {
            return rejected != null ? rejected : unchanged(session);
        }
        boolean thinking = session.isThinking() && modelSupportsThinking;
        List<RuntimeEntryDTO> entries = entriesFactory.apply(session);
        requireOne(mapper.updateSessionModel(sessionId, modelId, thinking, updatedAt), "session model was not updated");
        session.setModelId(modelId);
        session.setThinking(thinking);
        appendConfigurationEntries(session, entries);
        markConfigurationUpdated(session, updatedAt);
        Long sourceEventSeq = entries.isEmpty() ? null : entries.getLast().getEntrySeq();
        return new SessionConfigurationUpdateDTO(SessionConfigurationUpdateDTO.Status.UPDATED, session, sourceEventSeq);
    }

    @Override
    @Transactional
    public SessionConfigurationUpdateDTO updateThinking(
            String sessionId,
            Long expectedVersion,
            boolean thinking,
            Consumer<RuntimeSessionDTO> admission,
            Function<RuntimeSessionDTO, RuntimeEntryDTO> entryFactory,
            OffsetDateTime updatedAt) {
        RuntimeSessionDTO session = lockSession(sessionId);
        SessionConfigurationUpdateDTO rejected = rejectConfigurationUpdate(session, expectedVersion);
        if (rejected != null) {
            return rejected;
        }
        admission.accept(session);
        if (session.isThinking() == thinking) {
            return unchanged(session);
        }
        RuntimeEntryDTO entry = entryFactory.apply(session);
        requireOne(
                mapper.updateSessionThinking(sessionId, thinking, updatedAt),
                "session thinking setting was not updated");
        session.setThinking(thinking);
        appendConfigurationEntries(session, List.of(entry));
        markConfigurationUpdated(session, updatedAt);
        return new SessionConfigurationUpdateDTO(
                SessionConfigurationUpdateDTO.Status.UPDATED, session, entry.getEntrySeq());
    }

    @Override
    @Transactional
    public SessionDeletionStatus beginDeletion(String sessionId, OffsetDateTime deletedAt) {
        RuntimeSessionDTO session = lockSession(sessionId);
        if (session == null) {
            return SessionDeletionStatus.NOT_FOUND;
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            return SessionDeletionStatus.BUSY;
        }
        mapper.insertTombstone(sessionId, deletedAt);
        mapper.insertCleanupTask(sessionId, deletedAt);
        mapper.deleteSession(sessionId);
        return SessionDeletionStatus.DELETED;
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
        mapper.deleteRecords(sessionId);
        mapper.deleteStats(sessionId);
        mapper.deleteSequence(sessionId);
        mapper.deleteMaterialized(sessionId);
        mapper.deleteCleanupTask(sessionId);
    }

    @Override
    @Transactional
    public void retryCleanup(String sessionId, OffsetDateTime now, OffsetDateTime nextAttemptAt, String lastError) {
        mapper.markCleanupRetry(sessionId, now, nextAttemptAt, lastError);
    }

    private void appendLocked(RuntimeSessionDTO session, RuntimeEntryDTO entry) {
        Long sequence = mapper.lockNextSequence(session.getId());
        if (sequence == null) {
            throw new IllegalStateException("session sequence is missing");
        }
        entry.setParentId(session.getActiveLeafId());
        entry.setEntrySeq(sequence);
        requireOne(mapper.insertEntry(entry), "runtime entry was not inserted");
        requireOne(mapper.incrementSequence(session.getId()), "session sequence was not incremented");
        session.setActiveLeafId(entry.getId());
    }

    private RuntimeSessionDTO lockSession(String sessionId) {
        RuntimeSessionDTO session = mapper.lockSessionForUpdate(sessionId);
        if (session != null) {
            // 先取得主行锁，再读取统计，避免锁等待后携带旧查询快照中的 Usage。
            session.setLifetimeUsage(
                    Objects.requireNonNull(mapper.findLifetimeUsage(sessionId), "session usage stats are missing"));
        }
        return session;
    }

    private void appendConfigurationEntries(RuntimeSessionDTO session, List<RuntimeEntryDTO> entries) {
        for (RuntimeEntryDTO entry : entries) {
            appendLocked(session, entry);
        }
        if (!entries.isEmpty()) {
            requireOne(
                    mapper.updateActiveLeafAnyState(session.getId(), session.getActiveLeafId()),
                    "session active leaf was not updated");
        }
    }

    private void appendRecordLocked(RuntimeRecordDTO record) {
        Long sequence = mapper.lockNextSequence(record.getSessionId());
        if (sequence == null) {
            throw new IllegalStateException("session sequence is missing");
        }
        record.setRecordSeq(sequence);
        requireOne(mapper.insertRecord(record), "runtime record was not inserted");
        requireOne(mapper.incrementSequence(record.getSessionId()), "session sequence was not incremented");
    }

    private void incrementMessageCount(RuntimeEntryDTO entry) {
        if (isMessageEntry(entry.getType())) {
            requireOne(mapper.incrementMessageCount(entry.getSessionId()), "session message count was not updated");
        }
    }

    private void accumulateUsageStats(String sessionId, Usage usage) {
        Usage value = usage == null ? Usage.empty() : usage;
        Cost cost = value.cost() == null ? Cost.empty() : value.cost();
        var delta = new RuntimeLifetimeUsageDTO();
        delta.setInput(value.input());
        delta.setOutput(value.output());
        delta.setCacheRead(value.cacheRead());
        delta.setCacheWrite(value.cacheWrite());
        delta.setTotalTokens(value.totalTokens());
        delta.setCostInput(BigDecimal.valueOf(cost.input()));
        delta.setCostOutput(BigDecimal.valueOf(cost.output()));
        delta.setCostCacheRead(BigDecimal.valueOf(cost.cacheRead()));
        delta.setCostCacheWrite(BigDecimal.valueOf(cost.cacheWrite()));
        delta.setCostTotal(BigDecimal.valueOf(cost.total()));
        requireOne(mapper.accumulateUsageStats(sessionId, delta), "session usage stats were not updated");
    }

    private static boolean isMessageEntry(String type) {
        return "user.message".equals(type) || "assistant.message.completed".equals(type) || "tool.result".equals(type);
    }

    private static void requireOne(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static SessionConfigurationUpdateDTO rejectConfigurationUpdate(
            RuntimeSessionDTO session, Long expectedVersion) {
        if (session == null) {
            return new SessionConfigurationUpdateDTO(SessionConfigurationUpdateDTO.Status.NOT_FOUND, null);
        }
        if (expectedVersion != null && session.getResourceVersion() != expectedVersion) {
            return new SessionConfigurationUpdateDTO(SessionConfigurationUpdateDTO.Status.VERSION_MISMATCH, session);
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            return new SessionConfigurationUpdateDTO(SessionConfigurationUpdateDTO.Status.BUSY, session);
        }
        return null;
    }

    private static void markConfigurationUpdated(RuntimeSessionDTO session, OffsetDateTime updatedAt) {
        session.setResourceVersion(session.getResourceVersion() + 1);
        session.setUpdatedAt(updatedAt);
    }

    private static SessionConfigurationUpdateDTO unchanged(RuntimeSessionDTO session) {
        return new SessionConfigurationUpdateDTO(SessionConfigurationUpdateDTO.Status.UNCHANGED, session);
    }
}
