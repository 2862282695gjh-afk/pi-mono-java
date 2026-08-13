/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;

/**
 * Immutable snapshot of the effective tool catalog.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolCatalogSnapshot(
        long version,
        Map<String, AgentTool> toolsByName,
        Map<String, ToolContributionSource> sourcesByName,
        List<String> diagnostics) {

    public ToolCatalogSnapshot {
        toolsByName = Collections.unmodifiableMap(new LinkedHashMap<>(toolsByName));
        sourcesByName = Collections.unmodifiableMap(new LinkedHashMap<>(sourcesByName));
        diagnostics = List.copyOf(diagnostics);
    }
}
