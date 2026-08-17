/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;

/**
 * Immutable snapshot of the effective tool catalog.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
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
