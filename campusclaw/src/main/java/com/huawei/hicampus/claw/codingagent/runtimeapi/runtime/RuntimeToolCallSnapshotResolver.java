/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.codingagent.tool.mate.CallMateTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从本次实际装配的工具固定每个调用的可选展示名称。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeToolCallSnapshotResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeToolCallSnapshotResolver.class);

    private final Map<String, AgentTool> toolsByName;

    private final Map<String, DisplayBinding> bindings = new ConcurrentHashMap<>();

    private RuntimeToolCallSnapshotResolver(List<AgentTool> tools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        tools.forEach(tool -> indexed.put(tool.name(), tool));
        toolsByName = Map.copyOf(indexed);
    }

    public static RuntimeToolCallSnapshotResolver from(List<AgentTool> tools) {
        return new RuntimeToolCallSnapshotResolver(List.copyOf(tools));
    }

    public static RuntimeToolCallSnapshotResolver empty() {
        return new RuntimeToolCallSnapshotResolver(List.of());
    }

    /**
     * 固定并返回该调用的可选展示名称。
     *
     * @param call 模型工具调用
     * @return 规范化后的展示名称；无有效名称时为 null
     */
    public String resolveDisplayName(ToolCall call) {
        DisplayBinding binding = bindings.computeIfAbsent(call.id(), ignored -> resolve(call));
        if (!binding.toolName().equals(call.name())) {
            LOGGER.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.tool.snapshot")
                    .addKeyValue("toolCallId", call.id())
                    .log("CampusClaw failure: tool call id was rebound to a different tool name");
            return null;
        }
        return binding.displayName();
    }

    private DisplayBinding resolve(ToolCall call) {
        AgentTool tool = toolsByName.get(call.name());
        if (tool == null) {
            return new DisplayBinding(call.name(), null);
        }
        try {
            String displayName = resolveDisplayName(call, tool);
            return new DisplayBinding(call.name(), displayName);
        } catch (RuntimeException failure) {
            LOGGER.atWarn()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.tool.snapshot")
                    .addKeyValue("toolCallId", call.id())
                    .addKeyValue("toolName", call.name())
                    .setCause(failure)
                    .log("Tool display name snapshot failed");
            return new DisplayBinding(call.name(), null);
        }
    }

    private static String resolveDisplayName(ToolCall call, AgentTool tool) {
        if (!BuiltInToolName.CALL_MATE_TOOL.externalName().equals(call.name())) {
            return normalize(tool.label());
        }
        return tool instanceof CallMateTool mateTool ? resolveMateDisplayName(call, mateTool) : null;
    }

    private static String resolveMateDisplayName(ToolCall call, CallMateTool tool) {
        Object value = call.arguments() == null ? null : call.arguments().get("tool");
        return value instanceof String toolName ? normalize(tool.bindInvocation(call.id(), toolName)) : null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record DisplayBinding(String toolName, String displayName) {}
}
