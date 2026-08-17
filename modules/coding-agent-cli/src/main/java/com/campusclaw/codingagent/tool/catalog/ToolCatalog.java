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
 * Production entry point for resolving effective tools.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ToolCatalog {

    Snapshot snapshot();

    Snapshot refresh();

    Snapshot refresh(ToolRefreshRequest request);

    List<AgentTool> resolve(ToolSelection selection);

    /**
     * Resolves tools for one cwd-specific context. Implementations may override this
     * to avoid changing the shared catalog snapshot.
     *
     * @param request scoped source context
     * @param selection visibility selection
     * @return tools resolved for the supplied context
     */
    default List<AgentTool> resolve(ToolRefreshRequest request, ToolSelection selection) {
        synchronized (this) {
            refresh(request);
            return resolve(selection);
        }
    }

    Runnable addChangeListener(ChangeListener listener);

    /** Immutable snapshot of the effective tool catalog. */
    record Snapshot(
            long version,
            Map<String, AgentTool> toolsByName,
            Map<String, ToolContributionSource> sourcesByName,
            List<String> diagnostics) {

        public Snapshot {
            toolsByName = Collections.unmodifiableMap(new LinkedHashMap<>(toolsByName));
            sourcesByName = Collections.unmodifiableMap(new LinkedHashMap<>(sourcesByName));
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Listener notified after a tool catalog snapshot changes. */
    @FunctionalInterface
    interface ChangeListener {

        void onToolsChanged(Snapshot previous, Snapshot current);
    }
}
