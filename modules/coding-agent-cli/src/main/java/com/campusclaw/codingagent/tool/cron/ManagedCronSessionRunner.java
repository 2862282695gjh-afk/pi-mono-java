/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.cron;

import java.util.List;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.model.ModelCatalogService;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.ManagedAgentSession;
import com.campusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.campusclaw.codingagent.tool.agent.BoundAgentTool;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionContext;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.cron.engine.CronAgentSessionRunner;

import org.springframework.stereotype.Component;

/**
 * 使用公共 AgentSessionFactory 和 Cron profile 执行绑定 Agent 的 Job。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ManagedCronSessionRunner implements CronAgentSessionRunner {

    private final AgentSessionFactory sessionFactory;

    private final ModelCatalogService modelCatalogService;

    private final SubagentExecutionService subagentExecutionService;

    public ManagedCronSessionRunner(
            AgentSessionFactory sessionFactory,
            ModelCatalogService modelCatalogService,
            SubagentExecutionService subagentExecutionService) {
        this.sessionFactory = sessionFactory;
        this.modelCatalogService = modelCatalogService;
        this.subagentExecutionService = subagentExecutionService;
    }

    @Override
    public String execute(String agentId, String prompt) {
        ThinkingLevel thinking = ThinkingLevel.OFF;
        var request = new ManagedAgentSessionRequest(
                agentId,
                ToolEntryPoint.CRON,
                this::resolveDefaultModel,
                thinking,
                null,
                (prepared, model) -> new BoundAgentTool(
                        prepared,
                        SubagentExecutionContext.root(prepared.agentId(), model, thinking),
                        subagentExecutionService),
                null,
                List.of(),
                List.of());
        try (ManagedAgentSession session = sessionFactory.create(request)) {
            session.agent().prompt(prompt).join();
            return extractAnswer(session);
        }
    }

    private Model resolveDefaultModel(PreparedAgentRuntime runtime) {
        String configured = runtime.metadata()
                .defaultModel()
                .orElseThrow(() -> new IllegalStateException("Cron Agent has no default model"));
        return modelCatalogService.getAvailableModels().stream()
                .filter(model -> matchesConfiguredModel(model, configured))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cron Agent default model is unavailable"));
    }

    private static String extractAnswer(ManagedAgentSession session) {
        var messages = session.agent().getState().getMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistant) {
                String answer = assistant.content().stream()
                        .filter(TextContent.class::isInstance)
                        .map(TextContent.class::cast)
                        .map(TextContent::text)
                        .filter(text -> text != null && !text.isBlank())
                        .reduce("", String::concat)
                        .trim();
                if (!answer.isEmpty()) {
                    return answer;
                }
            }
        }
        return "";
    }

    private static boolean matchesConfiguredModel(Model model, String configured) {
        int slash = configured.indexOf('/');
        if (slash < 0) {
            return model.id().equals(configured);
        }
        String provider = configured.substring(0, slash);
        String modelId = configured.substring(slash + 1);
        return model.provider().value().equals(provider) && model.id().equals(modelId);
    }
}
