/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
    private final Map<String, Decision> decisions;

    private RuntimeToolPermissionPolicy(Map<String, Decision> decisions) {
        this.decisions = Map.copyOf(decisions);
    }

    public static RuntimeToolPermissionPolicy from(PreparedAgentRuntime runtime) {
        Map<String, Decision> decisions = new LinkedHashMap<>();
        merge(decisions, runtime.metadata().bindingTools());
        runtime.skills().forEach(skill -> merge(decisions, skill.bindingTools()));
        return new RuntimeToolPermissionPolicy(decisions);
    }

    public static RuntimeToolPermissionPolicy builtInsOnly() {
        return new RuntimeToolPermissionPolicy(Map.of());
    }

    public Decision decide(ToolCall call) {
        if (!BuiltInToolName.CALL_MATE_TOOL.externalName().equals(call.name())) {
            return Decision.ALLOW;
        }
        Object value = call.arguments() == null ? null : call.arguments().get("tool");
        return value instanceof String name ? decisions.getOrDefault(name, Decision.DENY) : Decision.DENY;
    }

    private static void merge(Map<String, Decision> decisions, java.util.List<BoundTool> tools) {
        for (BoundTool tool : tools) {
            if (tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            Decision decision = parse(tool.permission());
            decisions.merge(tool.name(), decision, (left, right) -> left == right ? left : Decision.DENY);
        }
    }

    public static Optional<String> uniqueAllowedToolId(PreparedAgentRuntime runtime, String toolName) {
        BindingDiagnosisDTO diagnosis = diagnoseBinding(runtime, toolName);
        if (diagnosis.status() != BindingStatus.BOUND || from(runtime).decisions.get(toolName) != Decision.ALLOW) {
            return Optional.empty();
        }
        return Optional.of(diagnosis.toolId());
    }

    public static BindingDiagnosisDTO diagnoseBinding(PreparedAgentRuntime runtime, String toolName) {
        List<BoundTool> matches = java.util.stream.Stream.concat(
                        runtime.metadata().bindingTools().stream(),
                        runtime.skills().stream().flatMap(skill -> skill.bindingTools().stream()))
                .filter(tool -> toolName.equals(tool.name()))
                .toList();
        if (matches.size() > 1) {
            return new BindingDiagnosisDTO(BindingStatus.AMBIGUOUS, null, matches.size());
        }
        if (matches.isEmpty()
                || matches.getFirst().id() == null
                || matches.getFirst().id().isBlank()) {
            return new BindingDiagnosisDTO(BindingStatus.MISSING, null, matches.size());
        }
        return new BindingDiagnosisDTO(BindingStatus.BOUND, matches.getFirst().id(), 1);
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
     * 可信快照中的工具绑定诊断状态。
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
}
