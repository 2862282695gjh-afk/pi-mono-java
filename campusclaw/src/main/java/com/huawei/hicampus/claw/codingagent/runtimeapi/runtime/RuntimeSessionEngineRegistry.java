/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.claw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.claw.codingagent.tool.agent.BoundAgentTool;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionContext;
import com.huawei.hicampus.claw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.claw.codingagent.tool.cron.AgentScopedCronToolFactory;

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
        acquireCapacity();
        try {
            RuntimeSessionHolder holder =
                    createHolder(sessionId, snapshot, model, thinking, messages, execution, credentials);
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

    public void complete(RuntimeSessionHolder holder, RuntimeActiveExecution execution) {
        holder.complete(execution);
        if (sessions.remove(holder.sessionId(), holder)) {
            holder.closeSession();
            capacity.release();
        }
    }

    public void lockOperation(String sessionId) {
        operationLock(sessionId).lock();
    }

    public void unlockOperation(String sessionId) {
        operationLock(sessionId).unlock();
    }

    private RuntimeSessionHolder createHolder(
            String sessionId,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            boolean thinking,
            List<Message> messages,
            RuntimeActiveExecution execution,
            MateCredentials credentials) {
        ManagedAgentSession session = createSession(snapshot, model, thinking, credentials);
        session.agent().replaceMessages(messages);
        RuntimeSessionHolder holder = new RuntimeSessionHolder(sessionId, snapshot, session, thinking);
        if (!holder.begin(execution)) {
            throw new IllegalStateException("new execution holder is already active");
        }
        return holder;
    }

    private ManagedAgentSession createSession(
            AgentDirectorySnapshotDTO snapshot, Model model, boolean thinking, MateCredentials credentials) {
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
                null,
                List.of(),
                List.of());
        return sessionFactory.create(request);
    }

    private static Model requireModelAllowed(
            com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime runtime, Model model) {
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
