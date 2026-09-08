/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
}
