/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.cron;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent.BoundAgentTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent.SubagentExecutionContext;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.mate.matecampusclaw.cron.engine.CronAgentSessionRunner;

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
