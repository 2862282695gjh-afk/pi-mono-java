/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 复用深度思考配置事务，在锁内当前模型上复核能力并生成领域事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SessionThinkingConfigurationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionThinkingConfigurationService.class);

    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver directoryResolver;

    private final RuntimeModelManager modelManager;

    private final RuntimeEntryCodec entryCodec;

    private final RuntimeCommittedEventFactory committedEventFactory;

    private final RuntimeEntryIdGenerator idGenerator;

    private final Clock clock;

    public SessionThinkingConfigurationService(
            RuntimeSessionRepository repository,
            AgentDirectoryResolver directoryResolver,
            RuntimeModelManager modelManager,
            RuntimeEntryCodec entryCodec,
            RuntimeCommittedEventFactory committedEventFactory,
            RuntimeEntryIdGenerator idGenerator,
            Clock clock) {
        this.repository = repository;
        this.directoryResolver = directoryResolver;
        this.modelManager = modelManager;
        this.entryCodec = entryCodec;
        this.committedEventFactory = committedEventFactory;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public SessionCommandResultDTO execute(String sessionId, String arguments) {
        try {
            if (arguments == null || arguments.isEmpty()) {
                return new SessionCommandResultDTO(requireSession(sessionId), false, null);
            }
            boolean requested = parseThinking(arguments);
            var current = requireSession(sessionId);
            if (!RuntimeSessionState.IDLE.matches(current.getState())) {
                throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            }
            var update = change(current, requested, null);
            return new SessionCommandResultDTO(
                    update.session(),
                    update.status() == SessionConfigurationUpdateDTO.Status.UPDATED,
                    update.sourceEventSeq());
        } catch (RuntimeApiException error) {
            if (error.errorCode() == RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED) {
                throw new RuntimeApiException(RuntimeErrorCode.MODEL_NOT_AVAILABLE);
            }
            throw error;
        } catch (RuntimeException error) {
            LOGGER.error(
                    "CampusClaw failure: operation=runtime.session.thinking.command, errorCode=COMMAND_EXECUTION_FAILED");
            throw new RuntimeApiException(RuntimeErrorCode.COMMAND_EXECUTION_FAILED);
        }
    }

    public SessionConfigurationUpdateDTO change(RuntimeSessionDTO current, boolean requested, Long expectedVersion) {
        var snapshot = requested ? directoryResolver.resolve(current.getAgentId()) : null;
        requireThinkingSupported(current, snapshot, requested);
        OffsetDateTime updatedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var update = repository.updateThinking(
                current.getId(),
                expectedVersion,
                requested,
                locked -> requireThinkingSupported(locked, snapshot, requested),
                locked -> entryCodec.thinkingChangedEntry(
                        locked.getId(), idGenerator.nextId(), locked.isThinking(), requested, "requested", updatedAt),
                committedEventFactory::sessionThinkingChanged,
                updatedAt);
        return switch (update.status()) {
            case UPDATED, UNCHANGED -> update;
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            case VERSION_MISMATCH -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        };
    }

    private void requireThinkingSupported(
            RuntimeSessionDTO current, AgentDirectorySnapshotDTO snapshot, boolean requested) {
        if (requested
                && !modelManager.resolveModel(snapshot, current.getModelId()).reasoning()) {
            throw new RuntimeApiException(RuntimeErrorCode.THINKING_NOT_SUPPORTED);
        }
    }

    private static boolean parseThinking(String arguments) {
        if (!"on".equals(arguments) && !"off".equals(arguments)) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        return "on".equals(arguments);
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
    }
}
