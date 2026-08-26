/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeFailures;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.stereotype.Service;

/**
 * Session 模型目录、模型切换和深度思考开关业务。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionConfigurationService {
    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver agentDirectoryResolver;

    private final RuntimeModelManager modelManager;

    private final SessionEtagFactory etagFactory;

    private final RuntimeSessionResponseAssembler responseAssembler;

    private final RuntimeEntryCodec entryCodec;

    private final RuntimeEntryIdGenerator entryIdGenerator;

    private final Clock clock;

    public RuntimeSessionConfigurationService(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver agentDirectoryResolver,
            RuntimeModelManager modelManager,
            SessionEtagFactory etagFactory,
            RuntimeSessionResponseAssembler responseAssembler,
            RuntimeEntryCodec entryCodec,
            RuntimeEntryIdGenerator entryIdGenerator,
            Clock clock) {
        this.repository = repository;
        this.agentDirectoryResolver = agentDirectoryResolver;
        this.modelManager = modelManager;
        this.etagFactory = etagFactory;
        this.responseAssembler = responseAssembler;
        this.entryCodec = entryCodec;
        this.entryIdGenerator = entryIdGenerator;
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
            OffsetDateTime updatedAt = now();
            SessionConfigurationUpdate update = repository.updateModel(
                    sessionId,
                    current.getResourceVersion(),
                    model.id(),
                    model.reasoning(),
                    modelChangeEntries(current, model, "requested", updatedAt),
                    updatedAt);
            return responseAssembler.getView(requireUpdated(update));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw RuntimeFailures.raise(
                    "runtime.session.model.update",
                    RuntimeErrorCode.SESSION_MODEL_UPDATE_FAILED,
                    error,
                    "sessionId",
                    sessionId);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> changeThinking(
            String sessionId, String ifMatch, ChangeThinkingRequestVO request) {
        requireThinkingRequest(request);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, ifMatch);
            requireThinkingSupported(current, request.getThinking());
            OffsetDateTime updatedAt = now();
            var entry = entryCodec.thinkingChangedEntry(
                    sessionId,
                    entryIdGenerator.nextId(),
                    current.isThinking(),
                    request.getThinking(),
                    "requested",
                    updatedAt);
            SessionConfigurationUpdate update = repository.updateThinking(
                    sessionId, current.getResourceVersion(), request.getThinking(), entry, updatedAt);
            return responseAssembler.getView(requireUpdated(update));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw RuntimeFailures.raise(
                    "runtime.session.thinking.update",
                    RuntimeErrorCode.SESSION_THINKING_UPDATE_FAILED,
                    error,
                    "sessionId",
                    sessionId);
        }
    }

    private RuntimeSessionDTO requireMutableSession(String sessionId, String ifMatch) {
        requireIfMatch(ifMatch);
        RuntimeSessionDTO session = requireSession(sessionId);
        String currentEtag = etagFactory.create(sessionId, session.getResourceVersion());
        if (!currentEtag.equals(ifMatch.trim())) {
            throw RuntimeFailures.raise(
                    "runtime.session.configuration.validate",
                    RuntimeErrorCode.SESSION_VERSION_MISMATCH,
                    "sessionId",
                    sessionId);
        }
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            throw RuntimeFailures.raise(
                    "runtime.session.configuration.validate", RuntimeErrorCode.SESSION_BUSY, "sessionId", sessionId);
        }
        return session;
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> RuntimeFailures.raise(
                        "runtime.session.find", RuntimeErrorCode.SESSION_NOT_FOUND, "sessionId", sessionId));
    }

    private AgentDirectorySnapshotDTO resolveAgent(RuntimeSessionDTO session) {
        return agentDirectoryResolver.resolve(session.getAgentId());
    }

    private List<com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO> modelChangeEntries(
            RuntimeSessionDTO current, Model model, String reason, OffsetDateTime updatedAt) {
        List<com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO> entries = new ArrayList<>();
        entries.add(entryCodec.modelChangedEntry(
                current.getId(), entryIdGenerator.nextId(), current.getModelId(), model.id(), reason, updatedAt));
        boolean nextThinking = current.isThinking() && model.reasoning();
        if (current.isThinking() != nextThinking) {
            entries.add(entryCodec.thinkingChangedEntry(
                    current.getId(),
                    entryIdGenerator.nextId(),
                    current.isThinking(),
                    nextThinking,
                    "modelCapability",
                    updatedAt));
        }
        return List.copyOf(entries);
    }

    private void requireThinkingSupported(RuntimeSessionDTO session, boolean requested) {
        if (!requested) {
            return;
        }
        Model model = modelManager.resolveModel(resolveAgent(session), session.getModelId());
        if (!model.reasoning()) {
            throw RuntimeFailures.raise(
                    "runtime.session.thinking.validate",
                    RuntimeErrorCode.THINKING_NOT_SUPPORTED,
                    "sessionId",
                    session.getId());
        }
    }

    private RuntimeSessionDTO requireUpdated(SessionConfigurationUpdate update) {
        return switch (update.status()) {
            case UPDATED, UNCHANGED -> update.session();
            case NOT_FOUND ->
                throw RuntimeFailures.raise(
                        "runtime.session.configuration.persist", RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY ->
                throw RuntimeFailures.raise("runtime.session.configuration.persist", RuntimeErrorCode.SESSION_BUSY);
            case VERSION_MISMATCH ->
                throw RuntimeFailures.raise(
                        "runtime.session.configuration.persist", RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        };
    }

    private static void requireModelRequest(ChangeModelRequestVO request) {
        if (request == null) {
            throw RuntimeFailures.raise("runtime.session.model.validate", RuntimeErrorCode.INVALID_MODEL_REQUEST);
        }
    }

    private static void requireThinkingRequest(ChangeThinkingRequestVO request) {
        if (request == null) {
            throw RuntimeFailures.raise("runtime.session.thinking.validate", RuntimeErrorCode.INVALID_THINKING_REQUEST);
        }
    }

    private static void requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw RuntimeFailures.raise("runtime.session.configuration.validate", RuntimeErrorCode.IF_MATCH_REQUIRED);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
