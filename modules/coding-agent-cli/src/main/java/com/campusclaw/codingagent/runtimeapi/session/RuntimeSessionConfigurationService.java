/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;
import com.campusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.stereotype.Service;

/**
 * Session 模型目录、模型切换和深度思考开关业务。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeSessionConfigurationService {
    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver agentDirectoryResolver;

    private final RuntimeModelManager modelManager;

    private final SessionEtagFactory etagFactory;

    private final RuntimeSessionResponseAssembler responseAssembler;

    private final Clock clock;

    public RuntimeSessionConfigurationService(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver agentDirectoryResolver,
            RuntimeModelManager modelManager,
            SessionEtagFactory etagFactory,
            RuntimeSessionResponseAssembler responseAssembler,
            Clock clock) {
        this.repository = repository;
        this.agentDirectoryResolver = agentDirectoryResolver;
        this.modelManager = modelManager;
        this.etagFactory = etagFactory;
        this.responseAssembler = responseAssembler;
        this.clock = clock;
    }

    public AvailableModelsResponseVO listModels(String sessionId) {
        RuntimeSessionDTO session = requireSession(sessionId);
        var models = modelManager.listAvailableModels(resolveAgent(session));
        return new AvailableModelsResponseVO(session.getModelId(), models);
    }

    public RuntimeSessionView<GetSessionResponseVO> changeModel(
            String sessionId, String ifMatch, ChangeModelRequestVO request) {
        requireModelRequest(request);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, ifMatch);
            Model model = modelManager.resolveAvailableModel(resolveAgent(current), request.getModelId());
            SessionConfigurationUpdate update = repository.updateModel(
                    sessionId, current.getResourceVersion(), model.id(), model.reasoning(), now());
            return responseAssembler.getView(requireUpdated(update));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_MODEL_UPDATE_FAILED, error);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> changeThinking(
            String sessionId, String ifMatch, ChangeThinkingRequestVO request) {
        requireThinkingRequest(request);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, ifMatch);
            requireThinkingSupported(current, request.getThinking());
            SessionConfigurationUpdate update =
                    repository.updateThinking(sessionId, current.getResourceVersion(), request.getThinking(), now());
            return responseAssembler.getView(requireUpdated(update));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_THINKING_UPDATE_FAILED, error);
        }
    }

    private RuntimeSessionDTO requireMutableSession(String sessionId, String ifMatch) {
        requireIfMatch(ifMatch);
        RuntimeSessionDTO session = requireSession(sessionId);
        String currentEtag = etagFactory.create(sessionId, session.getResourceVersion());
        if (!currentEtag.equals(ifMatch.trim())) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
        }
        return session;
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
    }

    private AgentDirectorySnapshotDTO resolveAgent(RuntimeSessionDTO session) {
        return agentDirectoryResolver.resolve(session.getAgentId());
    }

    private void requireThinkingSupported(RuntimeSessionDTO session, boolean requested) {
        if (!requested) {
            return;
        }
        Model model = modelManager.resolveModel(resolveAgent(session), session.getModelId());
        if (!model.reasoning()) {
            throw new RuntimeApiException(RuntimeErrorCode.THINKING_NOT_SUPPORTED);
        }
    }

    private RuntimeSessionDTO requireUpdated(SessionConfigurationUpdate update) {
        return switch (update.status()) {
            case UPDATED, UNCHANGED -> update.session();
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            case VERSION_MISMATCH -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        };
    }

    private static void requireModelRequest(ChangeModelRequestVO request) {
        if (request == null) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_MODEL_REQUEST);
        }
    }

    private static void requireThinkingRequest(ChangeThinkingRequestVO request) {
        if (request == null) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_THINKING_REQUEST);
        }
    }

    private static void requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new RuntimeApiException(RuntimeErrorCode.IF_MATCH_REQUIRED);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
