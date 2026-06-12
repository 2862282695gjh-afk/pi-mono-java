/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;

import org.springframework.stereotype.Component;

/**
 * Tool source backed by Spring-discovered {@link AgentTool} beans.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class SpringAgentToolSource implements ToolSource {

    private final List<AgentTool> tools;

    public SpringAgentToolSource(List<AgentTool> tools) {
        this.tools = List.copyOf(tools != null ? tools : List.of());
    }

    @Override
    public List<ToolContribution> load(ToolSourceContext context) {
        return tools.stream()
                .map(tool -> ToolContribution.add(tool, ToolContributionSource.system("spring"), 100))
                .toList();
    }
}
