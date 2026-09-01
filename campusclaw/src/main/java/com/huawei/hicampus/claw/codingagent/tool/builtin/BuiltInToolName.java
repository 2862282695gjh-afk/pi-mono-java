/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.Arrays;

import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;

/**
 * 定义允许向模型暴露的内置工具名称及其执行模式。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public enum BuiltInToolName {
    READ("Read", ToolExecutionMode.PARALLEL),
    FIND("Find", ToolExecutionMode.PARALLEL),
    GREP("Grep", ToolExecutionMode.PARALLEL),
    LS("Ls", ToolExecutionMode.PARALLEL),
    CRON("Cron", ToolExecutionMode.SEQUENTIAL),
    LIST_MATE_TOOLS("ListMateTools", ToolExecutionMode.PARALLEL),
    CALL_MATE_TOOL("CallMateTool", ToolExecutionMode.SEQUENTIAL),
    AGENT("Agent", ToolExecutionMode.SEQUENTIAL);

    private final String externalName;

    private final ToolExecutionMode executionMode;

    BuiltInToolName(String externalName, ToolExecutionMode executionMode) {
        this.externalName = externalName;
        this.executionMode = executionMode;
    }

    public String externalName() {
        return externalName;
    }

    public ToolExecutionMode executionMode() {
        return executionMode;
    }

    /**
     * 按区分大小写的外部名称解析工具。
     *
     * @param value 配置中的名称
     * @return 对应内置工具
     * @throws IllegalArgumentException 名称未知或大小写不正确时抛出
     */
    public static BuiltInToolName fromExternalName(String value) {
        return Arrays.stream(values())
                .filter(tool -> tool.externalName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown built-in tool name: " + value));
    }
}
