/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;

import org.springframework.stereotype.Component;

/**
 * Tool source backed by Spring-discovered {@link AgentTool} beans.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
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
