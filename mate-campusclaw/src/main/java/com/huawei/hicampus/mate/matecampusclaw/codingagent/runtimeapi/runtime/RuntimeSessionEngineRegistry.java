/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.queue.MessageQueue.DeliveryMode;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.ReadOperations;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.read.ReadTool;

import org.springframework.stereotype.Component;

/**
 * 仅保存活动执行 Agent 的进程内注册表，不缓存 idle Session。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeSessionEngineRegistry {
    private static final int OPERATION_LOCK_STRIPES = 256;

    private final ConcurrentHashMap<String, RuntimeSessionHolder> sessions = new ConcurrentHashMap<>();

    private final ReentrantLock[] operationLocks = createOperationLocks();

    private final CampusClawAiService aiService;

    private final ReadOperations readOperations;

    private final RuntimeAgentPromptLoader promptLoader;

    private final Semaphore capacity;

    public RuntimeSessionEngineRegistry(
            CampusClawAiService aiService,
            ReadOperations readOperations,
            RuntimeAgentPromptLoader promptLoader,
            RuntimeExecutionProperties properties) {
        this.aiService = aiService;
        this.readOperations = readOperations;
        this.promptLoader = promptLoader;
        this.capacity = new Semaphore(properties.getMaxActive());
    }

    public RuntimeSessionHolder register(
            String sessionId,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            boolean thinking,
            List<Message> messages,
            RuntimeActiveExecution execution) {
        acquireCapacity();
        try {
            RuntimeSessionHolder holder = createHolder(sessionId, snapshot, model, thinking, messages, execution);
            if (sessions.putIfAbsent(sessionId, holder) != null) {
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
            RuntimeActiveExecution execution) {
        Agent agent = createAgent(snapshot, model, thinking);
        agent.replaceMessages(messages);
        RuntimeSessionHolder holder = new RuntimeSessionHolder(sessionId, snapshot, agent);
        if (!holder.begin(execution)) {
            throw new IllegalStateException("new execution holder is already active");
        }
        return holder;
    }

    private Agent createAgent(AgentDirectorySnapshotDTO snapshot, Model model, boolean thinking) {
        Agent agent = new Agent(aiService);
        agent.setModel(model);
        agent.setSystemPrompt(promptLoader.load(snapshot.runtimeDirectory()));
        agent.setTools(List.of(new ReadTool(readOperations, snapshot.runtimeDirectory())));
        agent.setThinkingLevel(thinking ? ThinkingLevel.MEDIUM : ThinkingLevel.OFF);
        agent.setSteeringMode(DeliveryMode.ONE_AT_A_TIME);
        agent.setFollowUpMode(DeliveryMode.ONE_AT_A_TIME);
        return agent;
    }

    private void acquireCapacity() {
        if (!capacity.tryAcquire()) {
            throw new RuntimeApiException(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED);
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
