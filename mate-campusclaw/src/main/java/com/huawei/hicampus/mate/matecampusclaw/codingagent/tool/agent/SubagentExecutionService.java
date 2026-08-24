/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.agent;

import java.util.List;
import java.util.concurrent.CompletionException;

import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.model.ModelCatalogService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSessionFactory;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.springframework.stereotype.Service;

/**
 * 解析直接绑定并通过公共 AgentSessionFactory 执行 Child Agent。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SubagentExecutionService {

    private static final int MAX_CHILD_DEPTH = 1;

    private final AgentSessionFactory sessionFactory;

    private final ModelCatalogService modelCatalogService;

    public SubagentExecutionService(AgentSessionFactory sessionFactory, ModelCatalogService modelCatalogService) {
        this.sessionFactory = sessionFactory;
        this.modelCatalogService = modelCatalogService;
    }

    public AgentToolResult execute(
            PreparedAgentRuntime parentRuntime,
            SubagentExecutionContext context,
            String agentName,
            String task,
            CancellationToken signal,
            AgentToolUpdateCallback onUpdate) {
        CancellationToken effectiveSignal = signal == null ? new CancellationToken() : signal;
        AgentToolUpdateCallback effectiveUpdate = onUpdate == null ? ignored -> {} : onUpdate;
        requireAvailableDepth(context);
        AgentReference binding = requireDirectBinding(parentRuntime, agentName);
        requireEnabled(binding);
        requireNotSelfBound(parentRuntime, binding);
        requireNoCycle(context, binding.id());
        ensureNotCancelled(effectiveSignal);
        return runChild(binding, context, agentName, task, effectiveSignal, effectiveUpdate);
    }

    private AgentToolResult runChild(
            AgentReference binding,
            SubagentExecutionContext context,
            String agentName,
            String task,
            CancellationToken signal,
            AgentToolUpdateCallback onUpdate) {
        var request = new ManagedAgentSessionRequest(
                binding.id(),
                ToolEntryPoint.CHILD_AGENT,
                runtime -> resolveAllowedChildModel(runtime, context.inheritedModel()),
                context.inheritedThinking(),
                null,
                null,
                runtime -> validateChildRuntime(binding, runtime),
                List.of(),
                List.of());
        try (ManagedAgentSession session = sessionFactory.create(request)) {
            signal.onCancel(session::abort);
            Runnable unsubscribe = session.agent().subscribe(event -> projectProgress(event, onUpdate));
            onUpdate.onUpdate(textResult("Child Agent started: " + agentName));
            if (signal.isCancelled()) {
                throw new IllegalStateException("Child Agent execution was cancelled");
            }
            try {
                session.prompt(task).join();
            } catch (CompletionException exception) {
                throw propagate(exception);
            } finally {
                unsubscribe.run();
            }
            if (signal.isCancelled()) {
                throw new IllegalStateException("Child Agent execution was cancelled");
            }
            onUpdate.onUpdate(textResult("Child Agent completed"));
            return textResult(extractFinalAnswer(session));
        }
    }

    private static void projectProgress(AgentEvent event, AgentToolUpdateCallback onUpdate) {
        if (event instanceof ToolExecutionStartEvent started) {
            onUpdate.onUpdate(textResult("Child tool started: " + started.toolName()));
        } else if (event instanceof ToolExecutionEndEvent ended) {
            String status = ended.isError() ? "failed" : "completed";
            onUpdate.onUpdate(textResult("Child tool " + status + ": " + ended.toolName()));
        }
    }

    private Model resolveChildModel(PreparedAgentRuntime childRuntime, Model inherited) {
        var configured = childRuntime.metadata().defaultModel();
        if (configured.isEmpty()) {
            return inherited;
        }
        String modelName = configured.get();
        return modelCatalogService.getAvailableModels().stream()
                .filter(model -> matchesConfiguredModel(model, modelName))
                .findFirst()
                .orElse(inherited);
    }

    private Model resolveAllowedChildModel(PreparedAgentRuntime childRuntime, Model inherited) {
        Model model = resolveChildModel(childRuntime, inherited);
        requireModelAllowed(childRuntime, model);
        return model;
    }

    private static void validateChildRuntime(AgentReference binding, PreparedAgentRuntime childRuntime) {
        requireEnabled(childRuntime);
        requireVersion(binding, childRuntime);
    }

    private static AgentReference requireDirectBinding(PreparedAgentRuntime runtime, String agentName) {
        AgentReference binding = runtime.childAgentsByName().get(agentName);
        if (binding == null) {
            throw new IllegalArgumentException("Child Agent is not directly bound: " + agentName);
        }
        return binding;
    }

    private static void requireAvailableDepth(SubagentExecutionContext context) {
        if (context.depth() >= MAX_CHILD_DEPTH) {
            throw new IllegalStateException("Child Agent depth limit exceeded");
        }
    }

    private static void requireNoCycle(SubagentExecutionContext context, String childAgentId) {
        if (context.ancestorAgentIds().contains(childAgentId)) {
            throw new IllegalStateException("Child Agent path contains a cycle");
        }
    }

    private static void requireVersion(AgentReference binding, PreparedAgentRuntime childRuntime) {
        if (!binding.version().equals(childRuntime.metadata().version())) {
            throw new IllegalStateException("Child Agent version does not match its binding");
        }
    }

    private static void requireEnabled(AgentReference binding) {
        if (!Boolean.TRUE.equals(binding.enabled())) {
            throw new IllegalStateException("Child Agent binding is disabled");
        }
    }

    private static void requireNotSelfBound(PreparedAgentRuntime parentRuntime, AgentReference binding) {
        if (parentRuntime.agentId().equals(binding.id())) {
            throw new IllegalStateException("Child Agent must not reference the current Agent");
        }
    }

    private static void requireEnabled(PreparedAgentRuntime childRuntime) {
        if (!Boolean.TRUE.equals(childRuntime.metadata().enabled())) {
            throw new IllegalStateException("Child Agent is disabled");
        }
    }

    private static void requireModelAllowed(PreparedAgentRuntime childRuntime, Model model) {
        boolean allowed = childRuntime.metadata().bindingModels().stream()
                .anyMatch(configured -> matchesConfiguredModel(model, configured));
        if (!allowed) {
            throw new IllegalStateException("Child Agent does not allow the selected model");
        }
    }

    private static String extractFinalAnswer(ManagedAgentSession session) {
        List<com.huawei.hicampus.mate.matecampusclaw.ai.types.Message> messages =
                session.agent().getState().getMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistant) {
                String text = assistant.content().stream()
                        .filter(TextContent.class::isInstance)
                        .map(TextContent.class::cast)
                        .map(TextContent::text)
                        .filter(value -> value != null && !value.isBlank())
                        .reduce("", String::concat)
                        .trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "Child Agent completed without a text answer.";
    }

    private static RuntimeException propagate(CompletionException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
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

    private static AgentToolResult textResult(String text) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), null);
    }

    private static void ensureNotCancelled(CancellationToken signal) {
        if (signal.isCancelled()) {
            throw new java.util.concurrent.CancellationException("Child Agent execution was cancelled");
        }
    }
}
