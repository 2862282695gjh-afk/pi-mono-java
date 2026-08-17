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

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.subagent.SubAgentRegistry;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
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
    private final ConcurrentHashMap<String, RuntimeSessionHolder> sessions = new ConcurrentHashMap<>();

    private final CampusClawAiService aiService;

    private final List<AgentTool> tools;

    private final SubAgentRegistry subAgentRegistry;

    public RuntimeSessionEngineRegistry(
            CampusClawAiService aiService, List<AgentTool> tools, SubAgentRegistry subAgentRegistry) {
        this.aiService = aiService;
        this.tools = List.copyOf(tools);
        this.subAgentRegistry = subAgentRegistry;
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

    public void abortAndRemove(String sessionId) {
        RuntimeSessionHolder holder = sessions.remove(sessionId);
        if (holder == null) {
            return;
        }
        holder.agent().abort();
        subAgentRegistry.cancelAll("session-delete");
    }

    private Agent createAgent(
            CampusClawAiService service, AgentRuntimeSnapshotDTO snapshot, Model model, boolean thinking) {
        Agent agent = new Agent(service);
        agent.setModel(model);
        agent.setSystemPrompt(readSystemPrompt(snapshot.runtimeDirectory()));
        agent.setTools(tools);
        agent.setThinkingLevel(thinking ? ThinkingLevel.MEDIUM : ThinkingLevel.OFF);
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
}
