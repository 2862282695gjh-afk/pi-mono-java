/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.queue.MessageQueue.DeliveryMode;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactor;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolAssemblyContext;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolSessionState;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 为 Runtime、Cron 和 Child 创建相同类型及执行语义的公共 Agent Session。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class AgentSessionFactory {

    private final CampusClawAiService aiService;

    private final AgentRuntimeManager runtimeManager;

    private final ConfiguredToolAssembler toolAssembler;

    private final ObjectProvider<MateToolsetFactory> mateToolsetFactoryProvider;

    private final RuntimeAgentPromptLoader promptLoader;

    private final SessionCompactor compactor;

    public AgentSessionFactory(
            CampusClawAiService aiService,
            AgentRuntimeManager runtimeManager,
            ConfiguredToolAssembler toolAssembler,
            ObjectProvider<MateToolsetFactory> mateToolsetFactoryProvider,
            RuntimeAgentPromptLoader promptLoader,
            SessionCompactor compactor) {
        this.aiService = aiService;
        this.runtimeManager = runtimeManager;
        this.toolAssembler = toolAssembler;
        this.mateToolsetFactoryProvider = mateToolsetFactoryProvider;
        this.promptLoader = promptLoader;
        this.compactor = compactor;
    }

    /**
     * 创建一个不共享消息、工具实例和 Mate 缓存的 Session。
     *
     * @param request 不可变创建参数
     * @return 新 Session
     * @throws IllegalStateException 受管 Agent 已禁用时抛出
     */
    public ManagedAgentSession create(ManagedAgentSessionRequest request) {
        PreparedAgentRuntime runtime = runtimeManager.prepare(request.agentId());
        request.runtimeValidator().accept(runtime);
        if (!Boolean.TRUE.equals(runtime.metadata().enabled())) {
            throw new IllegalStateException("Managed Agent is disabled");
        }
        com.huawei.hicampus.mate.matecampusclaw.ai.types.Model model =
                java.util.Objects.requireNonNull(request.modelResolver().apply(runtime), "resolved model");
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create(runtime.agentId(), runtime.agentRoot());
        MateToolsetFactory mateToolsetFactory = mateToolsetFactoryProvider.getIfAvailable();
        MateToolSessionState mateState = mateToolsetFactory == null
                ? null
                : mateToolsetFactory.createSession(
                        runtime.agentId(), runtime.skillIdsByName(), request.mateCredentials());
        ToolAssemblyContext context = new ToolAssemblyContext(
                request.entryPoint(),
                runtime,
                model,
                request.thinkingLevel(),
                boundary,
                runtime.skillIdsByName(),
                runtime.childAgentsByName().keySet().stream().toList(),
                mateState,
                contextualSupplier(request.cronToolFactory(), runtime, model),
                contextualSupplier(request.agentToolFactory(), runtime, model));
        List<AgentTool> tools = toolAssembler.assemble(request.entryPoint(), context);
        Agent agent = configureAgent(request, runtime, model, tools);
        return new ManagedAgentSession(runtime, request.entryPoint(), agent, tools, compactor);
    }

    private Agent configureAgent(
            ManagedAgentSessionRequest request,
            PreparedAgentRuntime runtime,
            com.huawei.hicampus.mate.matecampusclaw.ai.types.Model model,
            List<AgentTool> tools) {
        Agent agent = new Agent(aiService);
        agent.setModel(model);
        agent.setSystemPrompt(promptLoader.load(runtime.agentRoot().resolve(".campusclaw")));
        agent.setThinkingLevel(request.thinkingLevel());
        agent.setTools(tools);
        agent.setSteeringMode(DeliveryMode.ONE_AT_A_TIME);
        agent.setFollowUpMode(DeliveryMode.ONE_AT_A_TIME);
        agent.setBeforeToolCall(AgentSessionHookChain.before(request.beforeHooks()));
        agent.setAfterToolCall(AgentSessionHookChain.after(request.afterHooks()));
        return agent;
    }

    private static java.util.function.Supplier<AgentTool> contextualSupplier(
            java.util.function.BiFunction<PreparedAgentRuntime, com.huawei.hicampus.mate.matecampusclaw.ai.types.Model, AgentTool> factory,
            PreparedAgentRuntime runtime,
            com.huawei.hicampus.mate.matecampusclaw.ai.types.Model model) {
        return factory == null ? null : () -> factory.apply(runtime, model);
    }
}
