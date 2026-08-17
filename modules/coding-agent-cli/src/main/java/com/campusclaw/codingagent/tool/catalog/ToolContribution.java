/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.function.Function;

import com.campusclaw.agent.tool.AgentTool;

/**
 * A tool plus the merge metadata needed by {@link DefaultToolCatalog}.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record ToolContribution(
        AgentTool tool,
        String targetName,
        ToolContributionSource source,
        int priority,
        MergeStrategy mergeStrategy,
        String replaces,
        Function<AgentTool, AgentTool> wrapper,
        boolean enabledByDefault) {

    /** Merge strategy for a tool contribution. */
    enum MergeStrategy {
        ADD,
        REPLACE,
        WRAP,
        DISABLE
    }

    public static ToolContribution add(AgentTool tool, ToolContributionSource source, int priority) {
        return new ToolContribution(tool, tool.name(), source, priority, MergeStrategy.ADD, null, null, true);
    }

    public static ToolContribution replace(
            AgentTool tool, ToolContributionSource source, int priority, String replaces) {
        return new ToolContribution(tool, tool.name(), source, priority, MergeStrategy.REPLACE, replaces, null, true);
    }

    public static ToolContribution wrap(
            String targetName, Function<AgentTool, AgentTool> wrapper, ToolContributionSource source, int priority) {
        return new ToolContribution(null, targetName, source, priority, MergeStrategy.WRAP, targetName, wrapper, true);
    }

    public static ToolContribution disable(String targetName, ToolContributionSource source, int priority) {
        return new ToolContribution(null, targetName, source, priority, MergeStrategy.DISABLE, targetName, null, false);
    }
}
