/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.SessionDeletionStatus;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.stereotype.Service;

/**
 * Runtime Session 创建、读取与删除业务。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionService {
    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver agentDirectoryResolver;

    private final RuntimeModelManager modelManager;

    private final RuntimeAgentPromptLoader promptLoader;

    private final SessionIdGenerator idGenerator;

    private final RuntimeSessionResponseAssembler responseAssembler;

    private final Clock clock;

    public RuntimeSessionService(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver agentDirectoryResolver,
            RuntimeModelManager modelManager,
            RuntimeAgentPromptLoader promptLoader,
            SessionIdGenerator idGenerator,
            RuntimeSessionResponseAssembler responseAssembler,
            Clock clock) {
        this.repository = repository;
        this.agentDirectoryResolver = agentDirectoryResolver;
        this.modelManager = modelManager;
        this.promptLoader = promptLoader;
        this.idGenerator = idGenerator;
        this.responseAssembler = responseAssembler;
        this.clock = clock;
    }

    public RuntimeSessionView<CreateSessionResponseVO> create(String agentId) {
        AgentDirectorySnapshotDTO snapshot = agentDirectoryResolver.resolve(agentId);
        promptLoader.validate(snapshot.runtimeDirectory());
        String modelId = modelManager.resolveDefaultModel(snapshot).id();
        OffsetDateTime now = now();
        RuntimeSessionDTO session = newSession(snapshot, modelId, now);
        try {
            repository.create(session);
            return responseAssembler.createView(session);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_INITIALIZATION_FAILED, error);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> get(String sessionId) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
        return responseAssembler.getView(session);
    }

    public void delete(String sessionId) {
        try {
            SessionDeletionStatus status = repository.beginDeletion(sessionId, now());
            if (status == SessionDeletionStatus.BUSY) {
                throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            }
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_DELETE_FAILED, error);
        }
    }

    private RuntimeSessionDTO newSession(AgentDirectorySnapshotDTO snapshot, String modelId, OffsetDateTime now) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(idGenerator.nextId());
        session.setAgentId(snapshot.agentId());
        session.setModelId(modelId);
        session.setState(RuntimeSessionState.IDLE.value());
        session.setThinking(true);
        session.setResourceVersion(1);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd(snapshot.runtimeDirectory().toString());
        return session;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
