/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 复用模型目录与事务切换能力，在锁内当前状态上生成权威领域事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SessionModelConfigurationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionModelConfigurationService.class);

    private final RuntimeSessionRepository repository;

    private final AgentDirectoryResolver directoryResolver;

    private final RuntimeModelManager modelManager;

    private final RuntimeEntryCodec entryCodec;

    private final RuntimeCommittedEventFactory committedEventFactory;

    private final RuntimeEntryIdGenerator idGenerator;

    private final Clock clock;

    public SessionModelConfigurationService(
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

    public ModelCommandResultDTO query(String sessionId) {
        var current = requireSession(sessionId);
        var snapshot = directoryResolver.resolve(current.getAgentId());
        return new ModelCommandResultDTO(current.getModelId(), modelManager.listAvailableModels(snapshot));
    }

    public CommandResultDTO execute(String sessionId, String arguments) {
        try {
            if (arguments == null || arguments.isEmpty()) {
                return query(sessionId);
            }
            var current = requireSession(sessionId);
            if (!RuntimeSessionState.IDLE.matches(current.getState())) {
                throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            }
            var snapshot = directoryResolver.resolve(current.getAgentId());
            var model = modelManager.resolveAvailableModel(snapshot, arguments);
            var update = update(current.getId(), null, model);
            return new SessionCommandResultDTO(
                    update.session(),
                    update.status() == SessionConfigurationUpdateDTO.Status.UPDATED,
                    update.sourceEventSeq());
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException error) {
            LOGGER.error(
                    "CampusClaw failure: operation=runtime.session.model.command, errorCode=COMMAND_EXECUTION_FAILED");
            throw new RuntimeApiException(RuntimeErrorCode.COMMAND_EXECUTION_FAILED);
        }
    }

    public SessionConfigurationUpdateDTO change(RuntimeSessionDTO current, String modelId, long expectedVersion) {
        var snapshot = directoryResolver.resolve(current.getAgentId());
        var model = modelManager.resolveAvailableModel(snapshot, modelId);
        return update(current.getId(), expectedVersion, model);
    }

    private SessionConfigurationUpdateDTO update(String sessionId, Long expectedVersion, Model model) {
        OffsetDateTime updatedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var update = repository.updateModel(
                sessionId,
                expectedVersion,
                model.id(),
                model.reasoning(),
                locked -> modelChangeEntries(locked, model, updatedAt),
                committedEventFactory::sessionConfiguration,
                updatedAt);
        return switch (update.status()) {
            case UPDATED, UNCHANGED -> update;
            case NOT_FOUND -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND);
            case BUSY -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            case VERSION_MISMATCH -> throw new RuntimeApiException(RuntimeErrorCode.SESSION_VERSION_MISMATCH);
        };
    }

    private List<RuntimeEntryDTO> modelChangeEntries(RuntimeSessionDTO current, Model model, OffsetDateTime updatedAt) {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        entries.add(entryCodec.modelChangedEntry(
                current.getId(), idGenerator.nextId(), current.getModelId(), model.id(), "requested", updatedAt));
        if (current.isThinking() && !model.reasoning()) {
            entries.add(entryCodec.thinkingChangedEntry(
                    current.getId(), idGenerator.nextId(), true, false, "modelCapability", updatedAt));
        }
        return List.copyOf(entries);
    }

    private RuntimeSessionDTO requireSession(String sessionId) {
        return repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
    }
}
