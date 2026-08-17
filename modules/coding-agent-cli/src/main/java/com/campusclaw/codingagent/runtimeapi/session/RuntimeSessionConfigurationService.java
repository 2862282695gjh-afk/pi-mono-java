/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.runtimeapi.auth.CallerAuthContext;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.SessionConfigurationUpdate;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotProvider;
import com.campusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;

/**
 * Session 模型目录、模型切换和深度思考开关的业务 Service。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class RuntimeSessionConfigurationService {
    private final RuntimeSessionRepository repository;

    private final AgentRuntimeSnapshotProvider snapshotProvider;

    private final RuntimeModelManager modelManager;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final SessionEtagFactory etagFactory;

    private final Validator validator;

    private final Clock clock;

    public RuntimeSessionConfigurationService(
            RuntimeSessionRepository repository,
            AgentRuntimeSnapshotProvider snapshotProvider,
            RuntimeModelManager modelManager,
            RuntimeSessionEngineRegistry engineRegistry,
            SessionEtagFactory etagFactory,
            Validator validator,
            Clock clock) {
        this.repository = repository;
        this.snapshotProvider = snapshotProvider;
        this.modelManager = modelManager;
        this.engineRegistry = engineRegistry;
        this.etagFactory = etagFactory;
        this.validator = validator;
        this.clock = clock;
    }

    public AvailableModelsResponseVO listModels(String sessionId, CallerAuthContext caller) {
        RuntimeSessionDTO session = requireOwnedSession(sessionId, caller, true);
        AgentRuntimeSnapshotDTO snapshot = resolveSnapshot(session);
        return new AvailableModelsResponseVO(session.getModelId(), modelManager.listAvailableModels(snapshot, caller));
    }

    public RuntimeSessionView<GetSessionResponseVO> changeModel(
            String sessionId, CallerAuthContext caller, String ifMatch, ChangeModelRequestVO request) {
        requireValid(request, RuntimeErrorCode.INVALID_MODEL_REQUEST);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, caller, ifMatch, true);
            AgentRuntimeSnapshotDTO snapshot = resolveSnapshot(current);
            Model model = modelManager.resolveAvailableModel(snapshot, caller, request.getModelId());
            SessionConfigurationUpdate update = withOperationLock(
                    sessionId,
                    () -> repository.updateModel(
                            sessionId,
                            caller.callerId(),
                            current.getResourceVersion(),
                            model.id(),
                            model.reasoning(),
                            now()),
                    (holder, result) ->
                            applyModelToEngine(holder, model, result.session().isThinking()));
            RuntimeSessionDTO updated = requireUpdated(update, true);
            return viewOf(updated);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_MODEL_UPDATE_FAILED, error);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> changeThinking(
            String sessionId, CallerAuthContext caller, String ifMatch, ChangeThinkingRequestVO request) {
        requireValid(request, RuntimeErrorCode.INVALID_THINKING_REQUEST);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, caller, ifMatch, false);
            requireThinkingSupported(current, request.getThinking());
            SessionConfigurationUpdate update = withOperationLock(
                    sessionId,
                    () -> repository.updateThinking(
                            sessionId, caller.callerId(), current.getResourceVersion(), request.getThinking(), now()),
                    (holder, result) ->
                            applyThinkingToEngine(holder, result.session().isThinking()));
            RuntimeSessionDTO updated = requireUpdated(update, false);
            return viewOf(updated);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_THINKING_UPDATE_FAILED, error);
        }
    }

    private RuntimeSessionDTO requireMutableSession(
            String sessionId, CallerAuthContext caller, String ifMatch, boolean modelChange) {
        requireIfMatch(ifMatch);
        RuntimeSessionDTO session = requireOwnedSession(sessionId, caller, false);
        String currentEtag = etagFactory.create(sessionId, session.getResourceVersion());
        if (!currentEtag.equals(ifMatch.trim())) {
            throw new RuntimeApiException(HttpStatus.PRECONDITION_FAILED, RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        }
        if (!"idle".equals(session.getState())) {
            throw busy(modelChange);
        }
        return session;
    }

    private RuntimeSessionDTO requireOwnedSession(String sessionId, CallerAuthContext caller, boolean listModels) {
        RuntimeSessionDTO session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));
        if (!session.getOwnerId().equals(caller.callerId())) {
            String chinese = listModels ? "当前调用方无权读取该 Session 的可选模型。" : "当前调用方无权修改该 Session。";
            String english = listModels
                    ? "The caller is not allowed to list models for this Session."
                    : "The caller is not allowed to modify this Session.";
            throw new RuntimeApiException(HttpStatus.FORBIDDEN, RuntimeErrorCode.FORBIDDEN, chinese, english);
        }
        return session;
    }

    private AgentRuntimeSnapshotDTO resolveSnapshot(RuntimeSessionDTO session) {
        return engineRegistry
                .find(session.getId())
                .map(RuntimeSessionHolder::snapshot)
                .filter(snapshot -> snapshot.bundleRevision().equals(session.getBundleRevision()))
                .orElseGet(() -> snapshotProvider.resolveRevision(session.getAgentId(), session.getBundleRevision()));
    }

    private void requireThinkingSupported(RuntimeSessionDTO session, boolean requested) {
        if (!requested) {
            return;
        }
        try {
            Model model = modelManager.resolveModel(resolveSnapshot(session), session.getModelId());
            if (model.reasoning()) {
                return;
            }
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.MANAGER_UNAVAILABLE) {
                throw new RuntimeApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_THINKING_UPDATE_FAILED, error);
            }
        }
        throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.THINKING_NOT_SUPPORTED);
    }

    private SessionConfigurationUpdate withOperationLock(
            String sessionId,
            Supplier<SessionConfigurationUpdate> update,
            BiConsumer<RuntimeSessionHolder, SessionConfigurationUpdate> applyToEngine) {
        engineRegistry.lockOperation(sessionId);
        try {
            SessionConfigurationUpdate result = update.get();
            engineRegistry.find(sessionId).ifPresent(holder -> applySuccessfulUpdate(holder, result, applyToEngine));
            return result;
        } finally {
            engineRegistry.unlockOperation(sessionId);
        }
    }

    private static void applySuccessfulUpdate(
            RuntimeSessionHolder holder,
            SessionConfigurationUpdate result,
            BiConsumer<RuntimeSessionHolder, SessionConfigurationUpdate> applyToEngine) {
        if (result.status() == SessionConfigurationUpdate.Status.UPDATED
                || result.status() == SessionConfigurationUpdate.Status.UNCHANGED) {
            applyToEngine.accept(holder, result);
        }
    }

    private RuntimeSessionDTO requireUpdated(SessionConfigurationUpdate update, boolean modelChange) {
        return switch (update.status()) {
            case UPDATED, UNCHANGED -> update.session();
            case NOT_FOUND -> throw new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND);
            case FORBIDDEN -> throw forbiddenModification();
            case BUSY -> throw busy(modelChange);
            case VERSION_MISMATCH ->
                throw new RuntimeApiException(
                        HttpStatus.PRECONDITION_FAILED, RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        };
    }

    private void applyModelToEngine(RuntimeSessionHolder holder, Model model, boolean thinking) {
        holder.agent().setModel(model);
        holder.agent().setThinkingLevel(thinking ? ThinkingLevel.MEDIUM : ThinkingLevel.OFF);
    }

    private void applyThinkingToEngine(RuntimeSessionHolder holder, boolean thinking) {
        holder.agent().setThinkingLevel(thinking ? ThinkingLevel.MEDIUM : ThinkingLevel.OFF);
    }

    private RuntimeSessionView<GetSessionResponseVO> viewOf(RuntimeSessionDTO session) {
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

    private <T> void requireValid(T request, RuntimeErrorCode errorCode) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, errorCode);
        }
    }

    private static void requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new RuntimeApiException(HttpStatus.PRECONDITION_REQUIRED, RuntimeErrorCode.IF_MATCH_REQUIRED);
        }
    }

    private static RuntimeApiException busy(boolean modelChange) {
        String chinese = modelChange ? "Session 运行期间不能切换模型。" : "Session 运行期间不能修改深度思考设置。";
        String english = modelChange
                ? "The model cannot be changed while the Session is running."
                : "Deep thinking cannot be changed while the Session is running.";
        return new RuntimeApiException(HttpStatus.CONFLICT, RuntimeErrorCode.SESSION_BUSY, chinese, english);
    }

    private static RuntimeApiException forbiddenModification() {
        return new RuntimeApiException(
                HttpStatus.FORBIDDEN,
                RuntimeErrorCode.FORBIDDEN,
                "当前调用方无权修改该 Session。",
                "The caller is not allowed to modify this Session.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
