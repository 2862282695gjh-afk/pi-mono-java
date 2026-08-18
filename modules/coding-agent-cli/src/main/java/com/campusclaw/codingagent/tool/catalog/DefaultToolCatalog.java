/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Copy-on-write tool catalog implementation. Contributions are additive and the
 * first contribution registered for a name wins; later duplicates are recorded
 * as diagnostics instead of overriding.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class DefaultToolCatalog implements ToolCatalog {

    private final List<ToolSource> sources;
    private volatile ToolSource.Context context;
    private volatile ToolCatalog.Snapshot snapshot;

    @Autowired
    public DefaultToolCatalog(List<ToolSource> sources) {
        this(sources, ToolSource.Context.defaults());
    }

    /**
     * Creates a catalog over the supplied sources with an explicit context.
     *
     * @param sources contribution sources
     * @param context initial refresh context
     */
    public DefaultToolCatalog(List<ToolSource> sources, ToolSource.Context context) {
        this.sources = List.copyOf(sources != null ? sources : List.of());
        this.context = context != null ? context : ToolSource.Context.defaults();
        this.snapshot = buildSnapshot(1L, this.context);
    }

    @Override
    public ToolCatalog.Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public synchronized ToolCatalog.Snapshot refresh() {
        var next = buildSnapshot(snapshot.version() + 1, context);
        snapshot = next;
        return next;
    }

    @Override
    public synchronized ToolCatalog.Snapshot refresh(ToolRefreshRequest request) {
        var nextContext = request != null ? request.toSourceContext(context) : context;
        var next = buildSnapshot(snapshot.version() + 1, nextContext);
        if (next.degraded()) {
            return new ToolCatalog.Snapshot(
                    snapshot.version(), snapshot.toolsByName(), snapshot.sourcesByName(), next.diagnostics(), true);
        }
        context = nextContext;
        snapshot = next;
        return next;
    }

    @Override
    public List<AgentTool> resolve(ToolSelection selection) {
        return resolve(snapshot, selection);
    }

    private List<AgentTool> resolve(ToolCatalog.Snapshot source, ToolSelection selection) {
        var effectiveSelection = selection != null ? selection : ToolSelection.all();
        if (effectiveSelection.noTools()) {
            return List.of();
        }
        var include = effectiveSelection.include();
        var exclude = effectiveSelection.exclude();
        return source.toolsByName().entrySet().stream()
                .filter(entry -> include.isEmpty() || include.contains(entry.getKey()))
                .filter(entry -> !exclude.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private ToolCatalog.Snapshot buildSnapshot(long version, ToolSource.Context context) {
        var toolsByName = new LinkedHashMap<String, AgentTool>();
        var sourcesByName = new LinkedHashMap<String, ToolContributionSource>();
        var diagnostics = new ArrayList<String>();
        boolean degraded = false;
        for (var source : sources) {
            List<ToolContribution> contributions;
            try {
                contributions = source.load(context);
            } catch (RuntimeException e) {
                degraded = true;
                diagnostics.add("Failed to load tool source: " + e.getMessage());
                continue;
            }
            for (var contribution : contributions) {
                var toolName = contribution.tool().name();
                if (toolsByName.containsKey(toolName)) {
                    diagnostics.add("Tool '" + toolName + "' already exists; ADD contribution ignored");
                    continue;
                }
                toolsByName.put(toolName, contribution.tool());
                sourcesByName.put(toolName, contribution.source());
            }
        }
        return new ToolCatalog.Snapshot(version, toolsByName, sourcesByName, diagnostics, degraded);
    }
}
