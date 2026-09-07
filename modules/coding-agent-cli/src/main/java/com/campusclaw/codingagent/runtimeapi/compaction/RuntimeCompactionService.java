/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.compaction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.CompactionAcceptanceStatus;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.springframework.stereotype.Service;

/**
 * 在共享操作锁内协调压缩的历史观察、资源准备、数据库准入和执行交接。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeCompactionService {
    private final RuntimeSessionRepository repository;

    private final RuntimeSessionEngineRegistry registry;

    private final AgentDirectoryResolver directories;

    private final RuntimeModelManager models;

    private final RuntimeEntryCodec codec;

    private final RuntimeCompactionCoordinator coordinator;

    private final RuntimeEntryIdGenerator ids;

    private final Clock clock;

    public RuntimeCompactionService(
            RuntimeSessionRepository repository,
            RuntimeSessionEngineRegistry registry,
            AgentDirectoryResolver directories,
            RuntimeModelManager models,
            RuntimeEntryCodec codec,
            RuntimeCompactionCoordinator coordinator,
            RuntimeEntryIdGenerator ids,
            Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.directories = directories;
        this.models = models;
        this.codec = codec;
        this.coordinator = coordinator;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * 观察并接受一次压缩；有效空上下文直接返回，不创建活动执行。
     *
     * @param sessionId 已由应用层授权的 Session 标识
     * @param credentials 仅供本次受管 Session 使用的 Mate 凭据
     * @param locale 本次领域投影语言
     * @return 不向已接受执行传播调用方取消的结果句柄
     */
    public CompletionStage<RuntimeCompactionResultDTO> compact(
            String sessionId, MateCredentials credentials, Locale locale) {
        return start(sessionId, credentials, locale).result();
    }

    /**
     * 接受压缩并返回仅作用于本次执行的中断能力；空上下文没有中断目标。
     *
     * @param sessionId 已授权的 Session 标识
     * @param credentials 本次请求的 Mate 凭据
     * @param locale 本次领域投影语言
     * @return 独立结果与中断能力
     */
    public RuntimeCompactionCall start(String sessionId, MateCredentials credentials, Locale locale) {
        return registry.withOperationLock(sessionId, () -> prepare(sessionId, credentials, locale));
    }

    private RuntimeCompactionCall prepare(String sessionId, MateCredentials credentials, Locale locale) {
        RuntimeCompactionSnapshotDTO observed = repository
                .observeCompaction(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
        RuntimeSessionDTO session = observed.session();
        if (!RuntimeSessionState.IDLE.matches(session.getState())) {
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
        }
        if (codec.toAgentContextEntryIds(observed.entries()).isEmpty()) {
            return new RuntimeCompactionCall(
                    CompletableFuture.completedStage(new RuntimeCompactionResultDTO(false, null)), () -> false);
        }
        AgentDirectorySnapshotDTO directory = directories.resolve(session.getAgentId());
        Model model = models.resolveAvailableModel(directory, session.getModelId());
        RuntimeCompactionExecution execution = new RuntimeCompactionExecution();
        execution.beginRun(ids.nextId());
        RuntimeSessionHolder holder = registry.register(
                sessionId,
                directory,
                model,
                session.isThinking(),
                codec.toAgentMessages(observed.entries(), model),
                execution,
                credentials);
        return acceptAndStart(session, holder, execution, locale);
    }

    private RuntimeCompactionCall acceptAndStart(
            RuntimeSessionDTO observed,
            RuntimeSessionHolder holder,
            RuntimeCompactionExecution execution,
            Locale locale) {
        boolean accepted = false;
        try {
            CompactionAcceptanceStatus status = repository.acceptCompaction(observed, now());
            if (status != CompactionAcceptanceStatus.ACCEPTED) {
                throw new RuntimeApiException(
                        status == CompactionAcceptanceStatus.NOT_FOUND
                                ? RuntimeErrorCode.SESSION_NOT_FOUND
                                : RuntimeErrorCode.SESSION_BUSY);
            }
            accepted = true;
            return new RuntimeCompactionCall(coordinator.start(holder, execution, locale), execution::interrupt);
        } catch (RuntimeException error) {
            if (!execution.completion().isDone()) {
                cleanup(error, () -> registry.complete(holder, execution));

                // 明确 ACCEPTED 才能回收持久化状态；提交结果不确定时不得盲目撤销竞争执行。
                if (accepted) {
                    cleanup(error, () -> repository.finishExecution(holder.sessionId(), now()));
                }
                execution.finishCompaction(null, error);
            }
            throw error;
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void cleanup(RuntimeException primary, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            if (primary != error) {
                primary.addSuppressed(error);
            }
        }
    }
}
