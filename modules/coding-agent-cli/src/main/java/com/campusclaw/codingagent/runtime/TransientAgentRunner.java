/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.List;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.session.AgentSession;
import com.campusclaw.codingagent.session.SessionConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 以完全瞬态的会话运行一次被委派的子调用：每次调用都新建一个
 * {@link AgentSession}（独立 Agent、AgentState、SkillRegistry 与工具快照），
 * 结束后即释放——没有按 Agent 常驻的 worker、线程池或共享可变运行状态。
 *
 * <p>子会话经 {@link DelegationWiring} 复用入口组装链（AI 服务、提示词
 * 构建器、Skill 加载/展开器、工具目录），加载子运行时的系统提示词与受管
 * Skill，并暴露 {@code activate_skill}；深度上限之内且自身持有有效绑定时，
 * 额外暴露 {@code invoke_agent} 供继续委派。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class TransientAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(TransientAgentRunner.class);

    /**
     * 执行一次子调用并返回子的最终答复。
     *
     * @param childRuntime 被委派子 Agent 的已准备运行时
     * @param childState 子会话的委派状态
     * @param task 交给子 Agent 的自包含任务指令
     * @param fallbackModel 子 Agent 未绑定缺省模型时使用的模型
     * @return 子 Agent 的最终助手文本
     * @throws AgentRuntimeException 子运行失败或未产生答复时抛出
     */
    public String run(
            PreparedAgentRuntime childRuntime, DelegationState childState, String task, String fallbackModel) {
        DelegationWiring wiring = childState.wiring();
        SessionConfig config = childState
                .dispatcher()
                .runtimeManager()
                .sessionConfig(new SessionConfig(fallbackModel, null, null, "one-shot"), childRuntime);
        AgentSession childSession = createSession(wiring, childRuntime, childState);
        childSession.initialize(config);
        try {
            childSession.prompt(task).join();
        } catch (Exception e) {
            throw new AgentRuntimeException(
                    "Delegated Agent " + childRuntime.agentId() + " failed: " + e.getMessage(), e);
        }
        return extractAnswer(childSession, childRuntime.agentId());
    }

    /**
     * 创建瞬态子会话；包私有以便测试替换为可控会话。
     *
     * @param wiring 入口协作者
     * @param childRuntime 已准备的子运行时
     * @param childState 安装到子会话上的委派状态
     * @return 尚未初始化的子会话
     */
    AgentSession createSession(DelegationWiring wiring, PreparedAgentRuntime childRuntime, DelegationState childState) {
        List<com.campusclaw.agent.tool.AgentTool> childLocalTools = new java.util.ArrayList<>(wiring.localTools());
        if (wiring.mateToolsetFactory() != null) {
            // 每个子会话一对会话私有的 Mate 工具与缓存,不与入口会话共享。
            childLocalTools.addAll(wiring.mateToolsetFactory().create());
        }
        AgentSession session = new AgentSession(
                wiring.aiService(),
                wiring.modelRegistry(),
                wiring.promptBuilder(),
                wiring.skillLoader(),
                wiring.skillExpander(),
                childLocalTools);
        if (wiring.toolCatalog() != null) {
            session.setToolCatalog(wiring.toolCatalog(), wiring.toolSelection());
        }
        session.setAgentRuntime(childRuntime, childState.dispatcher().runtimeManager());
        session.setDelegationState(childState);
        return session;
    }

    private static String extractAnswer(AgentSession session, String childAgentId) {
        List<Message> history = session.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!(history.get(i) instanceof AssistantMessage message)) {
                continue;
            }
            if (message.stopReason() == StopReason.ERROR) {
                throw new AgentRuntimeException(
                        "Delegated Agent " + childAgentId + " ended with error: " + message.errorMessage());
            }
            String text = assistantText(message);
            if (!text.isBlank()) {
                log.debug("delegated agent {} produced an answer of {} chars", childAgentId, text.length());
                return text;
            }
        }
        throw new AgentRuntimeException("Delegated Agent " + childAgentId + " produced no answer");
    }

    private static String assistantText(AssistantMessage message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextContent text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
