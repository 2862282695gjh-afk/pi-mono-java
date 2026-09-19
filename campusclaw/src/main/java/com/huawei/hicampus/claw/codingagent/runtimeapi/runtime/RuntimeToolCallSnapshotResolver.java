/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从本次实际装配的工具和可信绑定快照解析可选展示名称。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeToolCallSnapshotResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeToolCallSnapshotResolver.class);

    private final Map<String, AgentTool> toolsByName;

    private final Map<String, DisplayBinding> mateBindings;

    private RuntimeToolCallSnapshotResolver(
            List<AgentTool> tools, List<BoundTool> agentTools, List<BoundTool> skillTools) {
        toolsByName = indexTools(tools);
        mateBindings = indexMateBindings(agentTools, skillTools);
    }

    public static RuntimeToolCallSnapshotResolver from(PreparedAgentRuntime runtime, List<AgentTool> tools) {
        List<BoundTool> skillTools = runtime.skills().stream()
                .flatMap(skill -> skill.bindingTools().stream())
                .toList();
        return new RuntimeToolCallSnapshotResolver(tools, runtime.metadata().bindingTools(), skillTools);
    }

    public static RuntimeToolCallSnapshotResolver empty() {
        return new RuntimeToolCallSnapshotResolver(List.of(), List.of(), List.of());
    }

    /**
     * 返回该调用在当前执行快照中的可选展示名称。
     *
     * @param call 模型工具调用
     * @return 规范化后的展示名称；元数据无效时为 null
     */
    public String resolveDisplayName(ToolCall call) {
        try {
            return resolve(call);
        } catch (RuntimeException failure) {
            LOGGER.atWarn()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.tool.display-name")
                    .addKeyValue("toolCallId", call == null ? null : call.id())
                    .setCause(failure)
                    .log("Tool display name resolution failed");
            return null;
        }
    }

    private String resolve(ToolCall call) {
        if (call == null || call.name() == null) {
            return null;
        }
        if (BuiltInToolName.CALL_MATE_TOOL.externalName().equals(call.name())) {
            return resolveMateDisplayName(call);
        }
        AgentTool tool = toolsByName.get(call.name());
        return tool == null ? null : normalize(tool.label());
    }

    private String resolveMateDisplayName(ToolCall call) {
        Object value = call.arguments() == null ? null : call.arguments().get("tool");
        if (!(value instanceof String toolName)) {
            return null;
        }
        DisplayBinding binding = mateBindings.get(toolName);
        return binding == null || binding.conflicted() ? null : binding.displayName();
    }

    private static Map<String, AgentTool> indexTools(List<AgentTool> tools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (AgentTool tool : tools) {
            String name = tool.name();
            if (name == null || name.isBlank() || conflicts.contains(name)) {
                continue;
            }
            if (indexed.putIfAbsent(name, tool) != null) {
                indexed.remove(name);
                conflicts.add(name);
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, DisplayBinding> indexMateBindings(
            List<BoundTool> agentTools, List<BoundTool> skillTools) {
        Map<String, DisplayBinding> indexed = new LinkedHashMap<>();
        mergeMateBindings(indexed, agentTools);
        mergeMateBindings(indexed, skillTools);
        return Map.copyOf(indexed);
    }

    private static void mergeMateBindings(Map<String, DisplayBinding> indexed, List<BoundTool> tools) {
        for (BoundTool tool : tools) {
            if (tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            DisplayBinding current = DisplayBinding.from(tool);
            indexed.merge(tool.name(), current, DisplayBinding::merge);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record DisplayBinding(String toolId, String displayName, boolean conflicted) {
        private static DisplayBinding from(BoundTool tool) {
            String toolId = normalize(tool.id());
            String displayName = normalize(tool.displayName());
            return new DisplayBinding(toolId, displayName, toolId == null || displayName == null);
        }

        private DisplayBinding merge(DisplayBinding other) {
            boolean hasConflict = conflicted
                    || other.conflicted
                    || !Objects.equals(toolId, other.toolId)
                    || !Objects.equals(displayName, other.displayName);
            return new DisplayBinding(toolId, displayName, hasConflict);
        }
    }
}
