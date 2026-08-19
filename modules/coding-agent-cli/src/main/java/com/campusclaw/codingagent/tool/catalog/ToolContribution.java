/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import com.campusclaw.agent.tool.AgentTool;

/**
 * A tool plus the attribution needed by {@link DefaultToolCatalog}. Contributions
 * are strictly additive: a source can introduce a tool by name, and the first
 * contribution for a name wins. There is deliberately no replace/wrap/disable
 * mechanism — overriding built-in tools would change the tool trust boundary and
 * has no production consumer today (see ADR-0011).
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record ToolContribution(AgentTool tool, ToolContributionSource source) {

    /**
     * Creates an additive contribution.
     *
     * @param tool   the tool to register under its own name
     * @param source attribution for diagnostics
     * @return the contribution
     */
    public static ToolContribution add(AgentTool tool, ToolContributionSource source) {
        return new ToolContribution(tool, source);
    }
}
