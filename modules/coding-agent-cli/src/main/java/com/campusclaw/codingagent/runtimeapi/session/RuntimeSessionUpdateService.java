/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionUpdateChangesDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionUpdateSpecificationDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.UpdateSessionRequestVO;
import com.campusclaw.codingagent.session.PlanPolicyPromptComposer;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolProperties;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 统一校验并原子更新 Session 的名称、模型、思考和模式。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeSessionUpdateService.class);

    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver directoryResolver;

    private final RuntimeModelManager modelManager;

    private final BuiltInToolProperties toolProperties;

    private final PlanPolicyPromptComposer promptComposer;

    private final RuntimeEntryCodec entryCodec;

    private final RuntimeCommittedEventFactory eventFactory;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeSessionResponseAssembler responseAssembler;

    private final SessionEtagFactory etagFactory;

    private final Clock clock;

    public RuntimeSessionUpdateService(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver directoryResolver,
            RuntimeModelManager modelManager,
            BuiltInToolProperties toolProperties,
            PlanPolicyPromptComposer promptComposer,
            RuntimeEntryCodec entryCodec,
            RuntimeCommittedEventFactory eventFactory,
            RuntimeEntryIdGenerator ids,
            RuntimeSessionResponseAssembler responseAssembler,
            SessionEtagFactory etagFactory,
            Clock clock) {
        this.repository = repository;
        this.directoryResolver = directoryResolver;
        this.modelManager = modelManager;
        this.toolProperties = toolProperties;
        this.promptComposer = promptComposer;
        this.entryCodec = entryCodec;
        this.eventFactory = eventFactory;
        this.ids = ids;
        this.responseAssembler = responseAssembler;
        this.etagFactory = etagFactory;
        this.clock = clock;
    }

    public RuntimeSessionView<GetSessionResponseVO> update(
            String sessionId, String ifMatch, UpdateSessionRequestVO request) {
        requireRequest(request);
        String expectedEtag = requireStrongEtag(ifMatch);
        RuntimeSessionDTO observed = requireSession(sessionId);
        requireObservedVersion(observed, expectedEtag);
        try {
            SessionUpdateSpecificationDTO specification = prepare(observed, request);
            var result = repository.updateSession(
                    sessionId, specification, this::entries, eventFactory::sessionConfiguration);
            return responseAssembler.getView(requireUpdated(result));
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            logFailure(sessionId, error);
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_UPDATE_FAILED);
        }
    }

    private SessionUpdateSpecificationDTO prepare(RuntimeSessionDTO observed, UpdateSessionRequestVO request) {
        var specification = new SessionUpdateSpecificationDTO();
        specification.setExpectedVersion(observed.getResourceVersion());
        specification.setDisplayNameSubmitted(request.isDisplayNameSubmitted());
        specification.setDisplayName(request.isDisplayNameSubmitted() ? normalizeName(request.getDisplayName()) : null);
        specification.setModelIdSubmitted(request.isModelIdSubmitted());
        specification.setThinkingSubmitted(request.isThinkingSubmitted());
        specification.setThinking(Boolean.TRUE.equals(request.getThinking()));
        specification.setModeSubmitted(request.isModeSubmitted());
        specification.setMode(request.getMode());
        specification.setUpdatedAt(now());
        prepareExecutionConfiguration(observed, request, specification);
        return specification;
    }

    private void prepareExecutionConfiguration(
            RuntimeSessionDTO observed, UpdateSessionRequestVO request, SessionUpdateSpecificationDTO specification) {
        AgentDirectorySnapshotDTO snapshot = null;
        Model targetModel = null;
        if (request.isModelIdSubmitted()) {
            snapshot = directoryResolver.resolve(observed.getAgentId());
            targetModel = modelManager.resolveAvailableModel(snapshot, request.getModelId());
            specification.setModelId(targetModel.id());
            specification.setTargetModelSupportsThinking(targetModel.reasoning());
        } else if (request.isThinkingSubmitted() && Boolean.TRUE.equals(request.getThinking())) {
            snapshot = directoryResolver.resolve(observed.getAgentId());
            targetModel = modelManager.resolveModel(snapshot, observed.getModelId());
            specification.setTargetModelSupportsThinking(targetModel.reasoning());
        }
        requireThinkingSupported(request, targetModel);
        requirePlanModeAvailable(observed, request, snapshot);
    }

    private void requirePlanModeAvailable(
            RuntimeSessionDTO observed, UpdateSessionRequestVO request, AgentDirectorySnapshotDTO snapshot) {
        if (!request.isModeSubmitted() || !ClawConstants.Agent.MODE_PLAN.equals(request.getMode())) {
            return;
        }
        List<String> names = toolProperties.toolsFor(ToolEntryPoint.RUNTIME).stream()
                .map(tool -> tool.externalName())
                .toList();
        if (!promptComposer.planModeAvailable(names)) {
            throw new RuntimeApiException(RuntimeErrorCode.PLAN_MODE_UNAVAILABLE);
        }
        if (snapshot == null) {
            directoryResolver.resolve(observed.getAgentId());
        }
    }

    private List<RuntimeEntryDTO> entries(SessionUpdateChangesDTO changes) {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        RuntimeSessionDTO before = changes.previous();
        OffsetDateTime at = now();
        if (changes.modelChanged()) {
            entries.add(entryCodec.modelChangedEntry(
                    before.getId(), ids.nextId(), before.getModelId(), changes.modelId(), "requested", at));
        }
        if (changes.thinkingChanged()) {
            entries.add(entryCodec.thinkingChangedEntry(
                    before.getId(),
                    ids.nextId(),
                    before.isThinking(),
                    changes.thinking(),
                    changes.thinkingReason(),
                    at));
        }
        if (changes.modeChanged()) {
            entries.add(entryCodec.modeChangedEntry(
                    before.getId(), ids.nextId(), before.getMode(), changes.mode(), "requested", null, null, at));
        }
        return List.copyOf(entries);
    }

    private String requireStrongEtag(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new RuntimeApiException(RuntimeErrorCode.IF_MATCH_REQUIRED);
        }
        String value = ifMatch.trim();
        if (!ClawConstants.RuntimeApi.STRONG_ETAG_PATTERN.matcher(value).matches()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_SESSION_UPDATE_REQUEST);
        }
        return value;
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
    }

    private void requireObservedVersion(RuntimeSessionDTO session, String expectedEtag) {
        String current = etagFactory.create(session.getId(), session.getResourceVersion());
        if (!current.equals(expectedEtag)) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        }
    }

    private static void requireThinkingSupported(UpdateSessionRequestVO request, Model targetModel) {
        if (request.isThinkingSubmitted()
                && Boolean.TRUE.equals(request.getThinking())
                && (targetModel == null || !targetModel.reasoning())) {
            throw new RuntimeApiException(RuntimeErrorCode.THINKING_NOT_SUPPORTED);
        }
    }

    private static String normalizeName(String value) {
        if (ClawConstants.Session.FORBIDDEN_DISPLAY_NAME_PATTERN.matcher(value).find()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_SESSION_UPDATE_REQUEST);
        }
        String normalized = value.strip();
        if (normalized.isEmpty()
                || normalized.getBytes(StandardCharsets.UTF_8).length > ClawConstants.Session.MAX_DISPLAY_NAME_BYTES) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_SESSION_UPDATE_REQUEST);
        }
        return normalized;
    }

    private static void requireRequest(UpdateSessionRequestVO request) {
        if (request == null || !request.isNonEmptyUpdate()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_SESSION_UPDATE_REQUEST);
        }
    }

    private static RuntimeSessionDTO requireUpdated(SessionConfigurationUpdateDTO update) {
        return switch (update.status()) {
            case UPDATED, UNCHANGED -> update.session();
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            case VERSION_MISMATCH -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static void logFailure(String sessionId, RuntimeException error) {
        LOGGER.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "runtime.session.update")
                .addKeyValue("errorCode", RuntimeErrorCode.SESSION_UPDATE_FAILED.name())
                .addKeyValue("sessionId", sessionId)
                .setCause(error)
                .log(
                        "CampusClaw failure: operation={}, errorCode={}",
                        "runtime.session.update",
                        RuntimeErrorCode.SESSION_UPDATE_FAILED.name());
    }
}
