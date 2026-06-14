/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.campusclaw.agent.tool.AgentTool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Copy-on-write tool catalog implementation.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class DefaultToolCatalog implements ToolCatalog {

    private final List<ToolSource> sources;
    private final CopyOnWriteArrayList<ToolChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile ToolSourceContext context;
    private volatile ToolCatalogSnapshot snapshot;

    @Autowired
    public DefaultToolCatalog(List<ToolSource> sources) {
        this(sources, ToolSourceContext.defaults());
    }

    public DefaultToolCatalog(List<ToolSource> sources, ToolSourceContext context) {
        this.sources = List.copyOf(sources != null ? sources : List.of());
        this.context = context != null ? context : ToolSourceContext.defaults();
        this.snapshot = buildSnapshot(1L, this.context);
    }

    @Override
    public ToolCatalogSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public synchronized ToolCatalogSnapshot refresh() {
        var previous = snapshot;
        var next = buildSnapshot(snapshot.version() + 1, context);
        snapshot = next;
        notifyListeners(previous, next);
        return next;
    }

    @Override
    public synchronized ToolCatalogSnapshot refresh(ToolRefreshRequest request) {
        var nextContext = request != null ? request.toSourceContext(context) : context;
        var next = buildSnapshot(snapshot.version() + 1, nextContext);
        if (hasSourceFailure(next)) {
            return new ToolCatalogSnapshot(
                    snapshot.version(), snapshot.toolsByName(), snapshot.sourcesByName(), next.diagnostics());
        }
        var previous = snapshot;
        context = nextContext;
        snapshot = next;
        notifyListeners(previous, next);
        return next;
    }

    @Override
    public List<AgentTool> resolve(ToolSelection selection) {
        var effectiveSelection = selection != null ? selection : ToolSelection.all();
        if (effectiveSelection.noTools()) {
            return List.of();
        }
        var include = effectiveSelection.include();
        var exclude = effectiveSelection.exclude();
        return snapshot.toolsByName().entrySet().stream()
                .filter(entry -> include.isEmpty() || include.contains(entry.getKey()))
                .filter(entry -> !exclude.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public Runnable addChangeListener(ToolChangeListener listener) {
        if (listener == null) {
            return () -> {};
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private ToolCatalogSnapshot buildSnapshot(long version, ToolSourceContext context) {
        var toolsByName = new LinkedHashMap<String, AgentTool>();
        var sourcesByName = new LinkedHashMap<String, ToolContributionSource>();
        var diagnostics = new ArrayList<String>();
        var contributions = loadContributions(context, diagnostics);
        contributions.sort(Comparator.comparingInt(ToolContribution::priority));
        for (var contribution : contributions) {
            applyContribution(contribution, context, toolsByName, sourcesByName, diagnostics);
        }
        return new ToolCatalogSnapshot(version, toolsByName, sourcesByName, diagnostics);
    }

    private List<ToolContribution> loadContributions(ToolSourceContext context, List<String> diagnostics) {
        var contributions = new ArrayList<ToolContribution>();
        for (var source : sources) {
            try {
                contributions.addAll(source.load(context));
            } catch (RuntimeException e) {
                diagnostics.add("Failed to load tool source: " + e.getMessage());
            }
        }
        return contributions;
    }

    private boolean hasSourceFailure(ToolCatalogSnapshot snapshot) {
        return snapshot.diagnostics().stream().anyMatch(message -> message.startsWith("Failed to load tool source"));
    }

    private void applyContribution(
            ToolContribution contribution,
            ToolSourceContext context,
            LinkedHashMap<String, AgentTool> toolsByName,
            LinkedHashMap<String, ToolContributionSource> sourcesByName,
            List<String> diagnostics) {
        if (!context.replacementEnabled() && contribution.mergeStrategy() != ToolMergeStrategy.ADD) {
            diagnostics.add("Tool '" + contribution.targetName() + "' " + contribution.mergeStrategy()
                    + " contribution ignored because replacement is disabled");
            return;
        }
        if (contribution.mergeStrategy() == ToolMergeStrategy.DISABLE) {
            toolsByName.remove(contribution.targetName());
            sourcesByName.remove(contribution.targetName());
            return;
        }
        if (contribution.mergeStrategy() == ToolMergeStrategy.WRAP) {
            applyWrap(contribution, toolsByName, sourcesByName, diagnostics);
            return;
        }
        var toolName = contribution.tool().name();
        if (contribution.mergeStrategy() == ToolMergeStrategy.ADD && toolsByName.containsKey(toolName)) {
            diagnostics.add("Tool '" + toolName + "' already exists; ADD contribution ignored");
            return;
        }
        if (contribution.mergeStrategy() == ToolMergeStrategy.REPLACE
                && contribution.replaces() != null
                && !contribution.replaces().equals(toolName)) {
            toolsByName.remove(contribution.replaces());
            sourcesByName.remove(contribution.replaces());
        }
        toolsByName.put(toolName, contribution.tool());
        sourcesByName.put(toolName, contribution.source());
    }

    private void applyWrap(
            ToolContribution contribution,
            LinkedHashMap<String, AgentTool> toolsByName,
            LinkedHashMap<String, ToolContributionSource> sourcesByName,
            List<String> diagnostics) {
        var targetName = contribution.targetName();
        var current = toolsByName.get(targetName);
        if (current == null) {
            diagnostics.add("Tool '" + targetName + "' does not exist; WRAP contribution ignored");
            return;
        }
        if (contribution.wrapper() == null) {
            diagnostics.add("Tool '" + targetName + "' WRAP contribution has no wrapper; ignored");
            return;
        }
        var wrapped = contribution.wrapper().apply(current);
        if (wrapped == null) {
            diagnostics.add("Tool '" + targetName + "' WRAP contribution returned null; ignored");
            return;
        }
        toolsByName.put(wrapped.name(), wrapped);
        if (!wrapped.name().equals(targetName)) {
            toolsByName.remove(targetName);
            sourcesByName.remove(targetName);
        }
        sourcesByName.put(wrapped.name(), contribution.source());
    }

    private void notifyListeners(ToolCatalogSnapshot previous, ToolCatalogSnapshot current) {
        for (var listener : listeners) {
            listener.onToolsChanged(previous, current);
        }
    }
}
