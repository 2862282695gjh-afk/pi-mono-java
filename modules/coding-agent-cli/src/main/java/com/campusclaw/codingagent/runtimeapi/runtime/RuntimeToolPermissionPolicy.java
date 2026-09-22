/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.PreparedToolInvocation;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.common.constant.ClawConstants;

/**
 * 从受管 Agent 与 Skill 的可信运行时快照解析 Mate 工具权限。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeToolPermissionPolicy {
    private static final Set<String> BUILT_IN_NAMES = java.util.Arrays.stream(BuiltInToolName.values())
            .map(BuiltInToolName::externalName)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final Map<String, BindingDecision> agentDecisions;

    private final Map<String, BindingDecision> skillDecisions;

    private RuntimeToolPermissionPolicy(
            Map<String, BindingDecision> agentDecisions, Map<String, BindingDecision> skillDecisions) {
        this.agentDecisions = Map.copyOf(agentDecisions);
        this.skillDecisions = Map.copyOf(skillDecisions);
    }

    public static RuntimeToolPermissionPolicy from(PreparedAgentRuntime runtime) {
        Map<String, BindingDecision> agent = new LinkedHashMap<>();
        Map<String, BindingDecision> skill = new LinkedHashMap<>();
        merge(agent, runtime.metadata().bindingTools());
        runtime.skills().forEach(boundSkill -> merge(skill, boundSkill.bindingTools()));
        return new RuntimeToolPermissionPolicy(agent, skill);
    }

    public static RuntimeToolPermissionPolicy builtInsOnly() {
        return new RuntimeToolPermissionPolicy(Map.of(), Map.of());
    }

    public Decision decide(ToolCall call) {
        if (BuiltInToolName.CALL_TOOL.externalName().equals(call.name())) {
            Object value = call.arguments() == null ? null : call.arguments().get("tool");
            return value instanceof String name ? decision(skillDecisions, name) : Decision.DENY;
        }
        if (BUILT_IN_NAMES.contains(call.name())) {
            return Decision.ALLOW;
        }
        return decision(agentDecisions, call.name());
    }

    public Decision decide(BeforeToolCallContext context) {
        PreparedToolInvocation invocation = context.preparedInvocation();
        if (invocation == null) {
            return decide(context.toolCall());
        }
        if (BuiltInToolName.CALL_TOOL.externalName().equals(context.toolCall().name())) {
            return decision(skillDecisions, invocation.authorizationTargetName(), invocation.authorizationTargetId());
        }
        if (BUILT_IN_NAMES.contains(context.toolCall().name())) {
            return Decision.ALLOW;
        }
        return decision(agentDecisions, invocation.authorizationTargetName(), invocation.authorizationTargetId());
    }

    private static Decision decision(Map<String, BindingDecision> decisions, String name) {
        BindingDecision binding = decisions.get(name);
        return binding == null || binding.conflicted() ? Decision.DENY : binding.decision();
    }

    private static Decision decision(Map<String, BindingDecision> decisions, String name, String id) {
        BindingDecision binding = decisions.get(name);
        if (binding == null || binding.conflicted() || !java.util.Objects.equals(binding.id(), id)) {
            return Decision.DENY;
        }
        return binding.decision();
    }

    private static void merge(Map<String, BindingDecision> decisions, java.util.List<BoundTool> tools) {
        for (BoundTool tool : tools) {
            if (tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            BindingDecision current = new BindingDecision(tool.id(), parse(tool.permission()), false);
            decisions.merge(tool.name(), current, BindingDecision::merge);
        }
    }

    private static Decision parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case ClawConstants.Mate.TOOL_PERMISSION_ALLOW -> Decision.ALLOW;
            case ClawConstants.Mate.TOOL_PERMISSION_ASK -> Decision.ASK;
            default -> Decision.DENY;
        };
    }

    /**
     * 可信工具权限决定。
     */
    public enum Decision {
        ALLOW,
        ASK,
        DENY
    }

    private record BindingDecision(String id, Decision decision, boolean conflicted) {

        private BindingDecision merge(BindingDecision other) {
            boolean conflict = conflicted
                    || other.conflicted
                    || !java.util.Objects.equals(id, other.id)
                    || decision != other.decision;
            return new BindingDecision(id, conflict ? Decision.DENY : decision, conflict);
        }
    }
}
