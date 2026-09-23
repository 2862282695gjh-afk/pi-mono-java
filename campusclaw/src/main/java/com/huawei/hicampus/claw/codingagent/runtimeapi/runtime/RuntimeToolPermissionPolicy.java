/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.PreparedToolInvocation;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

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

    public static Optional<String> uniqueAllowedToolId(PreparedAgentRuntime runtime, String toolName) {
        List<BoundTool> matches = matchingBindings(runtime, toolName);
        BindingDiagnosisDTO diagnosis = diagnoseBinding(matches);
        if (diagnosis.status() != BindingStatus.BOUND
                || matches.stream().anyMatch(tool -> parse(tool.permission()) != Decision.ALLOW)) {
            return Optional.empty();
        }
        return Optional.of(diagnosis.toolId());
    }

    public static BindingDiagnosisDTO diagnoseBinding(PreparedAgentRuntime runtime, String toolName) {
        return diagnoseBinding(matchingBindings(runtime, toolName));
    }

    private static List<BoundTool> matchingBindings(PreparedAgentRuntime runtime, String toolName) {
        return java.util.stream.Stream.concat(
                        runtime.metadata().bindingTools().stream(),
                        runtime.skills().stream().flatMap(skill -> skill.bindingTools().stream()))
                .filter(tool -> java.util.Objects.equals(toolName, tool.name()))
                .toList();
    }

    private static BindingDiagnosisDTO diagnoseBinding(List<BoundTool> matches) {
        if (matches.isEmpty() || matches.stream().anyMatch(tool -> missingId(tool.id()))) {
            return new BindingDiagnosisDTO(BindingStatus.MISSING, null, matches.size());
        }
        String toolId = matches.getFirst().id();
        if (matches.stream().anyMatch(tool -> !toolId.equals(tool.id()))) {
            return new BindingDiagnosisDTO(BindingStatus.AMBIGUOUS, null, matches.size());
        }
        return new BindingDiagnosisDTO(BindingStatus.BOUND, toolId, matches.size());
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

    private static void merge(Map<String, BindingDecision> decisions, List<BoundTool> tools) {
        for (BoundTool tool : tools) {
            if (tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            BindingDecision current = new BindingDecision(tool.id(), parse(tool.permission()), missingId(tool.id()));
            decisions.merge(tool.name(), current, BindingDecision::merge);
        }
    }

    private static boolean missingId(String id) {
        return id == null || id.isBlank();
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

    /**
     * 可信工具绑定诊断状态。
     */
    public enum BindingStatus {
        BOUND,
        MISSING,
        AMBIGUOUS
    }

    /**
     * 工具绑定诊断结果，只携带安全的工具标识和匹配数量。
     *
     * @param status 绑定状态
     * @param toolId 唯一绑定的工具标识
     * @param matchingCount 同名绑定数量
     */
    public record BindingDiagnosisDTO(BindingStatus status, String toolId, int matchingCount) {}

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
