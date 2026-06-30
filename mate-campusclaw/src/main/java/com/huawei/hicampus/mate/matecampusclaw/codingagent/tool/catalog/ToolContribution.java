/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.util.function.Function;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;

/**
 * A tool plus the merge metadata needed by {@link DefaultToolCatalog}.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolContribution(
        AgentTool tool,
        String targetName,
        ToolContributionSource source,
        int priority,
        ToolMergeStrategy mergeStrategy,
        String replaces,
        Function<AgentTool, AgentTool> wrapper,
        boolean enabledByDefault) {

    public static ToolContribution add(AgentTool tool, ToolContributionSource source, int priority) {
        return new ToolContribution(tool, tool.name(), source, priority, ToolMergeStrategy.ADD, null, null, true);
    }

    public static ToolContribution replace(
            AgentTool tool, ToolContributionSource source, int priority, String replaces) {
        return new ToolContribution(
                tool, tool.name(), source, priority, ToolMergeStrategy.REPLACE, replaces, null, true);
    }

    public static ToolContribution wrap(
            String targetName, Function<AgentTool, AgentTool> wrapper, ToolContributionSource source, int priority) {
        return new ToolContribution(
                null, targetName, source, priority, ToolMergeStrategy.WRAP, targetName, wrapper, true);
    }

    public static ToolContribution disable(String targetName, ToolContributionSource source, int priority) {
        return new ToolContribution(
                null, targetName, source, priority, ToolMergeStrategy.DISABLE, targetName, null, false);
    }
}
