/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.SessionDeletionStatus;
import com.campusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runtime Session 创建、读取与删除业务。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeSessionService {
    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver agentDirectoryResolver;

    private final RuntimeModelManager modelManager;

    private final SessionIdGenerator idGenerator;

    private final SessionEtagFactory etagFactory;

    private final Clock clock;

    public RuntimeSessionService(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver agentDirectoryResolver,
            RuntimeModelManager modelManager,
            SessionIdGenerator idGenerator,
            SessionEtagFactory etagFactory,
            Clock clock) {
        this.repository = repository;
        this.agentDirectoryResolver = agentDirectoryResolver;
        this.modelManager = modelManager;
        this.idGenerator = idGenerator;
        this.etagFactory = etagFactory;
        this.clock = clock;
    }

    public RuntimeSessionView<CreateSessionResponseVO> create(String agentId) {
        AgentDirectorySnapshotDTO snapshot = agentDirectoryResolver.resolve(agentId);
        String modelId = modelManager.resolveDefaultModel(snapshot).id();
        OffsetDateTime now = now();
        RuntimeSessionDTO session = newSession(snapshot, modelId, now);
        try {
            repository.create(session);
            return createViewOf(session);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_INITIALIZATION_FAILED, error);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> get(String sessionId) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));
        return getViewOf(session);
    }

    public void delete(String sessionId) {
        try {
            SessionDeletionStatus status = repository.beginDeletion(sessionId, now());
            if (status == SessionDeletionStatus.BUSY) {
                throw new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_BUSY);
            }
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_DELETE_FAILED, error);
        }
    }

    private RuntimeSessionDTO newSession(
            AgentDirectorySnapshotDTO snapshot, String modelId, OffsetDateTime now) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(idGenerator.nextId());
        session.setAgentId(snapshot.agentId());
        session.setModelId(modelId);
        session.setState("idle");
        session.setThinking(false);
        session.setResourceVersion(1);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd(snapshot.agentDirectory().toString());
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
        return new RuntimeSessionView<>(resource, etag(session));
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
        return new RuntimeSessionView<>(resource, etag(session));
    }

    private String etag(RuntimeSessionDTO session) {
        return etagFactory.create(session.getId(), session.getResourceVersion());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
