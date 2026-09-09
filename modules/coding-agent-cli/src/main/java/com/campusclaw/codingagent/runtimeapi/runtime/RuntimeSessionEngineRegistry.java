/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.ManagedAgentSession;
import com.campusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.campusclaw.codingagent.tool.agent.BoundAgentTool;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionContext;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.cron.AgentScopedCronToolFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 仅保存活动执行 Agent 的进程内注册表，不缓存 idle Session。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeSessionEngineRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeSessionEngineRegistry.class);

    private static final int OPERATION_LOCK_STRIPES = 256;

    private final ConcurrentHashMap<String, RuntimeSessionHolder> sessions = new ConcurrentHashMap<>();

    private final ReentrantLock[] operationLocks = createOperationLocks();

    private final AgentSessionFactory sessionFactory;

    private final SubagentExecutionService subagentExecutionService;

    private final AgentScopedCronToolFactory cronToolFactory;

    private final Semaphore capacity;

    public RuntimeSessionEngineRegistry(
            AgentSessionFactory sessionFactory,
            SubagentExecutionService subagentExecutionService,
            AgentScopedCronToolFactory cronToolFactory,
            RuntimeExecutionProperties properties) {
        this.sessionFactory = sessionFactory;
        this.subagentExecutionService = subagentExecutionService;
        this.cronToolFactory = cronToolFactory;
        this.capacity = new Semaphore(properties.getMaxActive());
    }

    public RuntimeSessionHolder register(
            String sessionId,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            boolean thinking,
            List<Message> messages,
            RuntimeActiveExecution execution,
            MateCredentials credentials) {
        return register(sessionId, snapshot, model, thinking, messages, execution, credentials, null);
    }

    /**
     * 在本次实际准备的 Agent 快照上执行入口准入，再注册活动执行。
     *
     * @param sessionId Session 标识
     * @param snapshot 模型解析时的目录快照
     * @param model 已解析的模型
     * @param thinking 是否启用 thinking
     * @param messages 已恢复的历史
     * @param execution 本次执行状态
     * @param credentials 本次 Mate 凭据
     * @param runtimeValidator 实际 Agent 快照的入口准入，可为空
     * @return 已注册的活动句柄
     */
    public RuntimeSessionHolder register(
            String sessionId,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            boolean thinking,
            List<Message> messages,
            RuntimeActiveExecution execution,
            MateCredentials credentials,
            Consumer<PreparedAgentRuntime> runtimeValidator) {
        acquireCapacity();
        try {
            RuntimeSessionHolder holder = createHolder(
                    sessionId, snapshot, model, thinking, messages, execution, credentials, runtimeValidator);
            if (sessions.putIfAbsent(sessionId, holder) != null) {
                holder.closeSession();
                throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
            }
            return holder;
        } catch (RuntimeException error) {
            capacity.release();
            throw error;
        }
    }

    public Optional<RuntimeSessionHolder> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<ExecutionTargetDTO> activeTargets(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return sessions.values().stream()
                .flatMap(holder -> holder.activeExecution().stream())
                .flatMap(execution -> execution.assignedTarget().stream())
                .sorted(Comparator.comparing(ExecutionTargetDTO::sessionId)
                        .thenComparing(ExecutionTargetDTO::executionId)
                        .thenComparing(ExecutionTargetDTO::segmentId))
                .limit(limit)
                .toList();
    }

    public void complete(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        if (!holder.complete(execution)) {
            return;
        }
        if (sessions.remove(holder.sessionId(), holder)) {
            try {
                holder.closeSession();
            } finally {
                capacity.release();
            }
        }
    }

    public <T> T withOperationLock(String sessionId, Supplier<T> operation) {
        ReentrantLock lock = operationLock(sessionId);
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    public void withOperationLock(String sessionId, Runnable operation) {
        withOperationLock(sessionId, () -> {
            operation.run();
            return null;
        });
    }

    private RuntimeSessionHolder createHolder(
            String sessionId,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            boolean thinking,
            List<Message> messages,
            RuntimeActiveExecution execution,
            MateCredentials credentials,
            Consumer<PreparedAgentRuntime> runtimeValidator) {
        ManagedAgentSession session =
                createSession(snapshot, model, thinking, execution, credentials, runtimeValidator);
        try {
            execution.bindToolPermissions(RuntimeToolPermissionPolicy.from(session.runtime()));
            session.agent().replaceMessages(messages);
            RuntimeSessionHolder holder = new RuntimeSessionHolder(sessionId, snapshot, session, thinking);
            if (!holder.begin(execution)) {
                throw new IllegalStateException("new execution holder is already active");
            }
            return holder;
        } catch (RuntimeException error) {
            try {
                session.close();
            } catch (RuntimeException closeError) {
                if (closeError != error) {
                    error.addSuppressed(closeError);
                }
            }
            throw error;
        }
    }

    private ManagedAgentSession createSession(
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            boolean thinking,
            RuntimeActiveExecution execution,
            MateCredentials credentials,
            Consumer<PreparedAgentRuntime> runtimeValidator) {
        ThinkingLevel level = thinking ? ThinkingLevel.MEDIUM : ThinkingLevel.OFF;
        var request = new ManagedAgentSessionRequest(
                snapshot.agentId(),
                ToolEntryPoint.RUNTIME,
                runtime -> requireModelAllowed(runtime, model),
                level,
                credentials,
                (runtime, ignored) -> cronToolFactory.create(runtime.agentId()),
                (runtime, resolvedModel) -> new BoundAgentTool(
                        runtime,
                        SubagentExecutionContext.root(runtime.agentId(), resolvedModel, level, credentials),
                        subagentExecutionService),
                runtimeValidator,
                List.of(execution::beforeToolCall),
                List.of());
        return sessionFactory.create(request);
    }

    private static Model requireModelAllowed(
            com.campusclaw.codingagent.runtime.PreparedAgentRuntime runtime, Model model) {
        boolean allowed = runtime.metadata().bindingModels().stream()
                .anyMatch(configured -> matchesConfiguredModel(model, configured));
        if (!allowed) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return model;
    }

    private static boolean matchesConfiguredModel(Model model, String configured) {
        String qualified = model.provider().value() + "/" + model.id();
        return model.id().equals(configured) || qualified.equals(configured);
    }

    private void acquireCapacity() {
        if (!capacity.tryAcquire()) {
            RuntimeErrorCode errorCode = RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED;
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.execution.capacity")
                    .addKeyValue("errorCode", errorCode.name())
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.execution.capacity",
                            errorCode.name());
            throw new RuntimeApiException(errorCode);
        }
    }

    private ReentrantLock operationLock(String sessionId) {
        int index = (sessionId.hashCode() & Integer.MAX_VALUE) % operationLocks.length;
        return operationLocks[index];
    }

    private static ReentrantLock[] createOperationLocks() {
        ReentrantLock[] locks = new ReentrantLock[OPERATION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }
}
