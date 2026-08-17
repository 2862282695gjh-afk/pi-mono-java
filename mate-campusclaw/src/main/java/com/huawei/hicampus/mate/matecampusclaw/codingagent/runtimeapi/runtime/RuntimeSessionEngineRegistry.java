/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.queue.MessageQueue.DeliveryMode;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 管理 Runtime Session 进程内 Agent 对象的注册表。
 *
 * <p>Spring 应用先创建该 Bean，ServerMode 启动前再注入实际 AI Service 和工具集合。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeSessionEngineRegistry {
    private static final int OPERATION_LOCK_STRIPES = 256;

    private final ConcurrentHashMap<String, RuntimeSessionHolder> sessions = new ConcurrentHashMap<>();

    private final ReentrantLock[] operationLocks = createOperationLocks();

    private final CampusClawAiService aiService;

    private final List<AgentTool> tools;

    public RuntimeSessionEngineRegistry(CampusClawAiService aiService, List<AgentTool> tools) {
        this.aiService = aiService;
        this.tools = List.copyOf(tools);
    }

    public RuntimeSessionHolder initialize(
            String sessionId, AgentRuntimeSnapshotDTO snapshot, Model model, boolean thinking) {
        Agent agent = createAgent(aiService, snapshot, model, thinking);
        var holder = new RuntimeSessionHolder(sessionId, snapshot, agent);
        RuntimeSessionHolder existing = sessions.putIfAbsent(sessionId, holder);
        if (existing != null) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_INITIALIZATION_FAILED);
        }
        return holder;
    }

    public Optional<RuntimeSessionHolder> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void lockOperation(String sessionId) {
        operationLock(sessionId).lock();
    }

    public void unlockOperation(String sessionId) {
        operationLock(sessionId).unlock();
    }

    public RuntimeSessionHolder restore(
            String sessionId, AgentRuntimeSnapshotDTO snapshot, Model model, boolean thinking, List<Message> messages) {
        return sessions.computeIfAbsent(sessionId, ignored -> {
            Agent agent = createAgent(aiService, snapshot, model, thinking);
            agent.replaceMessages(messages);
            return new RuntimeSessionHolder(sessionId, snapshot, agent);
        });
    }

    public void abortAndRemove(String sessionId) {
        RuntimeSessionHolder holder = sessions.remove(sessionId);
        if (holder == null) {
            return;
        }
        holder.agent().abort();
    }

    private Agent createAgent(
            CampusClawAiService service, AgentRuntimeSnapshotDTO snapshot, Model model, boolean thinking) {
        Agent agent = new Agent(service);
        agent.setModel(model);
        agent.setSystemPrompt(readSystemPrompt(snapshot.runtimeDirectory()));
        agent.setTools(tools);
        agent.setThinkingLevel(thinking ? ThinkingLevel.MEDIUM : ThinkingLevel.OFF);
        agent.setSteeringMode(DeliveryMode.ONE_AT_A_TIME);
        agent.setFollowUpMode(DeliveryMode.ONE_AT_A_TIME);
        return agent;
    }

    private static String readSystemPrompt(Path runtimeDirectory) {
        Path promptFile = runtimeDirectory.resolve(".campusagent/SYSTEM.md");
        if (!Files.isRegularFile(promptFile)) {
            return "";
        }
        try {
            return Files.readString(promptFile);
        } catch (IOException error) {
            throw new RuntimeApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, RuntimeErrorCode.SESSION_INITIALIZATION_FAILED, error);
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
