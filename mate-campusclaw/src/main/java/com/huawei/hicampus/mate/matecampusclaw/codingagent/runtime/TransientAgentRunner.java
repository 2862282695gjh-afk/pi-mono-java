/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.SessionConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one delegated child invocation as a fully transient session: a fresh
 * {@link AgentSession} (own Agent, AgentState, SkillRegistry and tool
 * snapshot) is created for every invocation and released afterwards — there
 * is no per-agent worker, thread pool or shared mutable run state.
 *
 * <p>The child session reuses the entry assembly chain (AI service, prompt
 * builder, skill loader/expander, tool catalog) through
 * {@link DelegationWiring}, loads the child runtime's system prompt and
 * managed skills, and exposes {@code activate_skill} plus — below the depth
 * cap with own valid bindings — {@code invoke_agent} for further delegation.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class TransientAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(TransientAgentRunner.class);

    /**
     * Executes one child invocation and returns the child's final answer.
     *
     * @param childRuntime  prepared runtime of the delegated child Agent
     * @param childState    delegation state of the child session
     * @param task          self-contained task instructions for the child
     * @param fallbackModel model used when the child binds no default model
     * @return the child Agent's final assistant text
     * @throws AgentRuntimeException when the child run fails or produces no
     *         answer
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
     * Creates the transient child session; package-private so tests can
     * substitute a controllable session.
     *
     * @param wiring      entry collaborators
     * @param childRuntime prepared child runtime
     * @param childState  delegation state installed on the child session
     * @return uninitialized child session
     */
    AgentSession createSession(DelegationWiring wiring, PreparedAgentRuntime childRuntime, DelegationState childState) {
        AgentSession session = new AgentSession(
                wiring.aiService(),
                wiring.modelRegistry(),
                wiring.promptBuilder(),
                wiring.skillLoader(),
                wiring.skillExpander(),
                wiring.localTools());
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
