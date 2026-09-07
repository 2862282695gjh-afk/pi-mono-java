/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionModelConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionThinkingConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Session 模型目录、模型切换和深度思考开关业务。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionConfigurationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeSessionConfigurationService.class);

    private final RuntimeSessionRepository repository;

    private final SessionModelConfigurationService modelService;

    private final SessionThinkingConfigurationService thinkingService;

    private final SessionEtagFactory etagFactory;

    private final RuntimeSessionResponseAssembler responseAssembler;

    public RuntimeSessionConfigurationService(
            RuntimeSessionRepository repository,
            SessionModelConfigurationService modelService,
            SessionThinkingConfigurationService thinkingService,
            SessionEtagFactory etagFactory,
            RuntimeSessionResponseAssembler responseAssembler) {
        this.repository = repository;
        this.modelService = modelService;
        this.thinkingService = thinkingService;
        this.etagFactory = etagFactory;
        this.responseAssembler = responseAssembler;
    }

    public AvailableModelsResponseVO listModels(String sessionId) {
        var result = modelService.query(sessionId);
        return new AvailableModelsResponseVO(result.currentModelId(), result.models());
    }

    public RuntimeSessionView<GetSessionResponseVO> changeModel(
            String sessionId, String ifMatch, ChangeModelRequestVO request) {
        requireModelRequest(request);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, ifMatch);
            SessionConfigurationUpdateDTO update =
                    modelService.change(current, request.getModelId(), current.getResourceVersion());
            return responseAssembler.getView(requireUpdated(update));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            RuntimeErrorCode errorCode = RuntimeErrorCode.SESSION_MODEL_UPDATE_FAILED;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.session.model.update")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("sessionId", sessionId)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.session.model.update",
                            errorCode.name());
            throw new RuntimeApiException(errorCode);
        }
    }

    public RuntimeSessionView<GetSessionResponseVO> changeThinking(
            String sessionId, String ifMatch, ChangeThinkingRequestVO request) {
        requireThinkingRequest(request);
        try {
            RuntimeSessionDTO current = requireMutableSession(sessionId, ifMatch);
            SessionConfigurationUpdateDTO update =
                    thinkingService.change(current, request.getThinking(), current.getResourceVersion());
            return responseAssembler.getView(requireUpdated(update));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            RuntimeErrorCode errorCode = RuntimeErrorCode.SESSION_THINKING_UPDATE_FAILED;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.session.thinking.update")
                    .addKeyValue("errorCode", errorCode.name())
                    .addKeyValue("sessionId", sessionId)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.session.thinking.update",
                            errorCode.name());
            throw new RuntimeApiException(errorCode);
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

    private RuntimeSessionDTO requireUpdated(SessionConfigurationUpdateDTO update) {
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
}
