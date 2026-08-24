/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.campusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance.Status;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final RuntimeSessionMapper mapper;

    private final ObjectMapper objectMapper;

    public MyBatisRuntimeSessionRepository(RuntimeSessionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void create(RuntimeSessionDTO session) {
        if (mapper.tombstoneExists(session.getId()) > 0) {
            throw new IllegalStateException("session id is permanently reserved");
        }
        mapper.insertSession(session);
        mapper.insertSequence(session.getId());
        mapper.insertMaterialized(session.getId(), materializedPayload(session));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeSessionDTO> find(String sessionId) {
        RuntimeSessionDTO session = mapper.findSession(sessionId);
        if (session != null) {
            session.setLifetimeUsage(readUsage(mapper.findLifetimeUsage(sessionId)));
        }
        return Optional.ofNullable(session);
    }

    @Override
    @Transactional
    public UserEventAcceptance acceptUserEvent(String sessionId, RuntimeEntryDTO entry, OffsetDateTime acceptedAt) {
        RuntimeSessionDTO session = mapper.lockSessionForUpdate(sessionId);
        if (session == null) {
            return new UserEventAcceptance(Status.NOT_FOUND, null);
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            return new UserEventAcceptance(Status.BUSY, session);
        }
        appendLocked(session, entry);
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
    public RuntimeEntryDTO appendEntry(RuntimeEntryDTO entry) {
        RuntimeSessionDTO session = mapper.lockSessionForUpdate(entry.getSessionId());
        if (session == null) {
            throw new IllegalStateException("session disappeared during execution");
        }
        appendLocked(session, entry);
        requireOne(mapper.updateActiveLeaf(entry.getSessionId(), entry.getId()), "session active leaf was not updated");
        accumulateUsage(entry);
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
    public SessionConfigurationUpdate updateModel(
            String sessionId,
            long expectedVersion,
            String modelId,
            boolean modelSupportsThinking,
            List<RuntimeEntryDTO> entries,
            OffsetDateTime updatedAt) {
        RuntimeSessionDTO session = mapper.lockSessionForUpdate(sessionId);
        SessionConfigurationUpdate rejected = rejectConfigurationUpdate(session, expectedVersion);
        if (rejected != null || session.getModelId().equals(modelId)) {
            return rejected != null ? rejected : unchanged(session);
        }
        boolean thinking = session.isThinking() && modelSupportsThinking;
        requireOne(mapper.updateSessionModel(sessionId, modelId, thinking, updatedAt), "session model was not updated");
        session.setModelId(modelId);
        session.setThinking(thinking);
        appendConfigurationEntries(session, entries);
        markConfigurationUpdated(session, updatedAt);
        return updated(session);
    }

    @Override
    @Transactional
    public SessionConfigurationUpdate updateThinking(
            String sessionId, long expectedVersion, boolean thinking, RuntimeEntryDTO entry, OffsetDateTime updatedAt) {
        RuntimeSessionDTO session = mapper.lockSessionForUpdate(sessionId);
        SessionConfigurationUpdate rejected = rejectConfigurationUpdate(session, expectedVersion);
        if (rejected != null || session.isThinking() == thinking) {
            return rejected != null ? rejected : unchanged(session);
        }
        requireOne(
                mapper.updateSessionThinking(sessionId, thinking, updatedAt),
                "session thinking setting was not updated");
        session.setThinking(thinking);
        appendConfigurationEntries(session, List.of(entry));
        markConfigurationUpdated(session, updatedAt);
        return updated(session);
    }

    @Override
    @Transactional
    public SessionDeletionStatus beginDeletion(String sessionId, OffsetDateTime deletedAt) {
        RuntimeSessionDTO session = mapper.lockSessionForUpdate(sessionId);
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

    private void appendConfigurationEntries(RuntimeSessionDTO session, List<RuntimeEntryDTO> entries) {
        for (RuntimeEntryDTO entry : entries) {
            appendLocked(session, entry);
            accumulateUsage(entry);
        }
        if (!entries.isEmpty()) {
            requireOne(
                    mapper.updateActiveLeafAnyState(session.getId(), session.getActiveLeafId()),
                    "session active leaf was not updated");
        }
    }

    private void accumulateUsage(RuntimeEntryDTO entry) {
        if (entry.getUsage() == null) {
            return;
        }
        var current = readUsage(mapper.lockLifetimeUsage(entry.getSessionId()));
        var accumulated = RuntimeUsageAccumulator.add(current, entry.getUsage());
        requireOne(
                mapper.updateLifetimeUsage(entry.getSessionId(), writeJson(accumulated)),
                "session lifetime usage was not updated");
    }

    private String materializedPayload(RuntimeSessionDTO session) {
        var payload = objectMapper.createObjectNode();
        payload.set("lifetimeUsage", objectMapper.valueToTree(session.getLifetimeUsage()));
        return writeJson(payload);
    }

    private com.campusclaw.ai.types.Usage readUsage(String value) {
        if (value == null || value.isBlank()) {
            return com.campusclaw.ai.types.Usage.empty();
        }
        try {
            return objectMapper.readValue(value, com.campusclaw.ai.types.Usage.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("session lifetime usage is invalid", error);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("runtime persistence value cannot be serialized", error);
        }
    }

    private static void requireOne(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static SessionConfigurationUpdate rejectConfigurationUpdate(
            RuntimeSessionDTO session, long expectedVersion) {
        if (session == null) {
            return new SessionConfigurationUpdate(SessionConfigurationUpdate.Status.NOT_FOUND, null);
        }
        if (session.getResourceVersion() != expectedVersion) {
            return new SessionConfigurationUpdate(SessionConfigurationUpdate.Status.VERSION_MISMATCH, session);
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            return new SessionConfigurationUpdate(SessionConfigurationUpdate.Status.BUSY, session);
        }
        return null;
    }

    private static void markConfigurationUpdated(RuntimeSessionDTO session, OffsetDateTime updatedAt) {
        session.setResourceVersion(session.getResourceVersion() + 1);
        session.setUpdatedAt(updatedAt);
    }

    private static SessionConfigurationUpdate updated(RuntimeSessionDTO session) {
        return new SessionConfigurationUpdate(SessionConfigurationUpdate.Status.UPDATED, session);
    }

    private static SessionConfigurationUpdate unchanged(RuntimeSessionDTO session) {
        return new SessionConfigurationUpdate(SessionConfigurationUpdate.Status.UNCHANGED, session);
    }
}
