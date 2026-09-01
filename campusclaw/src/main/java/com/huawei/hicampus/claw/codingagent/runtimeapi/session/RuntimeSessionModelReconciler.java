/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;

import org.springframework.stereotype.Service;

/**
 * 在接受用户 Entry 前依据最新 Agent 目录校准 Session 模型和 thinking。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionModelReconciler {
    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver directoryResolver;

    private final RuntimeModelManager modelManager;

    private final RuntimeEntryCodec entryCodec;

    private final RuntimeEntryIdGenerator idGenerator;

    private final Clock clock;

    public RuntimeSessionModelReconciler(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver directoryResolver,
            RuntimeModelManager modelManager,
            RuntimeEntryCodec entryCodec,
            RuntimeEntryIdGenerator idGenerator,
            Clock clock) {
        this.repository = repository;
        this.directoryResolver = directoryResolver;
        this.modelManager = modelManager;
        this.entryCodec = entryCodec;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public ReconciledRuntimeSession reconcile(RuntimeSessionDTO session) {
        AgentDirectorySnapshotDTO snapshot = directoryResolver.resolve(session.getAgentId());
        Model current = resolveCurrent(snapshot, session.getModelId());
        if (current != null) {
            return new ReconciledRuntimeSession(session, snapshot, current, List.of());
        }
        Model fallback = resolveFallback(snapshot);
        OffsetDateTime updatedAt = now();
        List<RuntimeEntryDTO> entries = changeEntries(session, fallback, updatedAt);
        SessionConfigurationUpdate update = repository.updateModel(
                session.getId(), session.getResourceVersion(), fallback.id(), fallback.reasoning(), entries, updatedAt);
        return new ReconciledRuntimeSession(requireUpdated(update), snapshot, fallback, entries);
    }

    private Model resolveCurrent(AgentDirectorySnapshotDTO snapshot, String modelId) {
        try {
            return modelManager.resolveAvailableModel(snapshot, modelId);
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
                throw error;
            }
            return null;
        }
    }

    private Model resolveFallback(AgentDirectorySnapshotDTO snapshot) {
        try {
            return modelManager.resolveAvailableModel(snapshot, snapshot.defaultModelId());
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
                throw error;
            }
            throw new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE);
        }
    }

    private List<RuntimeEntryDTO> changeEntries(RuntimeSessionDTO session, Model fallback, OffsetDateTime updatedAt) {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        entries.add(entryCodec.modelChangedEntry(
                session.getId(), idGenerator.nextId(), session.getModelId(), fallback.id(), "agentRefresh", updatedAt));
        boolean nextThinking = session.isThinking() && fallback.reasoning();
        if (session.isThinking() != nextThinking) {
            entries.add(entryCodec.thinkingChangedEntry(
                    session.getId(),
                    idGenerator.nextId(),
                    session.isThinking(),
                    nextThinking,
                    "modelCapability",
                    updatedAt));
        }
        return List.copyOf(entries);
    }

    private RuntimeSessionDTO requireUpdated(SessionConfigurationUpdate update) {
        return switch (update.status()) {
            case UPDATED -> update.session();
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            case VERSION_MISMATCH -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
            case UNCHANGED -> throw new IllegalStateException("fallback model did not change the session");
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
