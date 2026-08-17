/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.campusclaw.codingagent.runtimeapi.auth.RuntimeAgentAuthorizer;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;
import com.campusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runtime Session 创建、读取与删除的业务编排 Service。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeSessionService {
    private final RuntimeSessionRepository repository;

    private final RuntimeAgentAuthorizer agentAuthorizer;

    private final AgentRuntimeSnapshotProvider snapshotProvider;

    private final RuntimeModelManager modelManager;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final SessionIdGenerator idGenerator;

    private final SessionEtagFactory etagFactory;

    private final Clock clock;

    public RuntimeSessionService(
            RuntimeSessionRepository repository,
            RuntimeAgentAuthorizer agentAuthorizer,
            AgentRuntimeSnapshotProvider snapshotProvider,
            RuntimeModelManager modelManager,
            RuntimeSessionEngineRegistry engineRegistry,
            SessionIdGenerator idGenerator,
            SessionEtagFactory etagFactory,
            Clock clock) {
        this.repository = repository;
        this.agentAuthorizer = agentAuthorizer;
        this.snapshotProvider = snapshotProvider;
        this.modelManager = modelManager;
        this.engineRegistry = engineRegistry;
        this.idGenerator = idGenerator;
        this.etagFactory = etagFactory;
        this.clock = clock;
    }

    public RuntimeSessionView<CreateSessionResponseVO> create(String agentId, CallerAuthContext caller) {
        requireCreateAllowed(agentId, caller);
        var snapshot = snapshotProvider.resolveCurrent(agentId);
        var model = modelManager.resolveDefaultModel(snapshot);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        RuntimeSessionDTO session = newSession(agentId, caller.callerId(), snapshot, model.id(), now);
        try {
            engineRegistry.initialize(session.getId(), snapshot, model, false);
            repository.create(session);
            return createViewOf(session);
        } catch (RuntimeException error) {
            engineRegistry.abortAndRemove(session.getId());
            if (error instanceof RuntimeApiException apiError) {
                throw apiError;
            }
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_INITIALIZATION_FAILED, error);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> get(String sessionId, CallerAuthContext caller) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));
        requireOwner(session, caller, "当前调用方无权访问该 Session。", "The caller is not allowed to access this Session.");
        return getViewOf(session);
    }

    public void delete(String sessionId, CallerAuthContext caller) {
        var existing = repository.find(sessionId);
        if (existing.isEmpty()) {
            return;
        }
        requireOwner(
                existing.get(), caller, "当前调用方无权删除该 Session。", "The caller is not allowed to delete this Session.");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            if (repository.beginDeletion(sessionId, now)) {
                engineRegistry.abortAndRemove(sessionId);
            }
        } catch (RuntimeException error) {
            if (error instanceof RuntimeApiException apiError) {
                throw apiError;
            }
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_DELETE_FAILED, error);
        }
    }

    private RuntimeSessionDTO newSession(
            String agentId,
            String ownerId,
            com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO snapshot,
            String modelId,
            OffsetDateTime now) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(idGenerator.nextId());
        session.setAgentId(agentId);
        session.setOwnerId(ownerId);
        session.setBundleRevision(snapshot.bundleRevision());
        session.setModelId(modelId);
        session.setState("idle");
        session.setThinking(false);
        session.setResourceVersion(1);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd(snapshot.runtimeDirectory().toString());
        return session;
    }

    private RuntimeSessionView<CreateSessionResponseVO> createViewOf(RuntimeSessionDTO session) {
        var resource = new CreateSessionResponseVO(
                session.getId(),
                session.getAgentId(),
                session.getModelId(),
                session.getState(),
                session.isThinking(),
                session.getCreatedAt());
        return new RuntimeSessionView<>(resource, etagFactory.create(session.getId(), session.getResourceVersion()));
    }

    private RuntimeSessionView<GetSessionResponseVO> getViewOf(RuntimeSessionDTO session) {
        var resource = new GetSessionResponseVO(
                session.getId(),
                session.getAgentId(),
                session.getModelId(),
                session.getState(),
                session.isThinking(),
                session.getCreatedAt(),
                session.getUpdatedAt());
        return new RuntimeSessionView<>(resource, etagFactory.create(session.getId(), session.getResourceVersion()));
    }

    private static void requireOwner(
            RuntimeSessionDTO session, CallerAuthContext caller, String chineseMessage, String englishMessage) {
        if (!session.getOwnerId().equals(caller.callerId())) {
            throw new RuntimeApiException(
                    HttpStatus.FORBIDDEN, RuntimeErrorCode.FORBIDDEN, chineseMessage, englishMessage);
        }
    }

    private void requireCreateAllowed(String agentId, CallerAuthContext caller) {
        if (!agentAuthorizer.canCreateSession(agentId, caller)) {
            throw new RuntimeApiException(
                    HttpStatus.FORBIDDEN,
                    RuntimeErrorCode.FORBIDDEN,
                    "当前调用方无权为该 Agent 创建 Session。",
                    "The caller is not allowed to create a Session for this Agent.");
        }
    }
}
