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
 * Name-index over the {@link AgentTool} beans registered in this process, with
 * include/exclude/noTools visibility selection. The catalog only indexes Spring
 * registered tools and in-tree extension contributions; it does not scan user
 * directories and does not create tools from declarations (ADR-0011).
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ToolCatalog {

    /**
     * Returns the current snapshot without reloading sources.
     *
     * @return immutable snapshot
     */
    Snapshot snapshot();

    /**
     * Reloads all sources with the current context.
     *
     * @return the new snapshot
     */
    Snapshot refresh();

    /**
     * Reloads all sources with a refreshed context.
     *
     * @param request refresh request carrying the working directory
     * @return the new snapshot, or the previous snapshot with appended diagnostics when a source failed
     */
    Snapshot refresh(ToolRefreshRequest request);

    /**
     * Resolves visible tools from the current snapshot.
     *
     * @param selection visibility selection; {@code null} selects all tools
     * @return tools matching the selection, in registration order
     */
    List<AgentTool> resolve(ToolSelection selection);

    /**
     * Immutable snapshot of the effective tool catalog.
     *
     * @param version monotonic snapshot version
     * @param toolsByName tool index by name
     * @param sourcesByName source attribution by tool name
     * @param diagnostics non-fatal merge notes
     * @param degraded whether any source failed to load during this snapshot build
     */
    record Snapshot(
            long version,
            Map<String, AgentTool> toolsByName,
            Map<String, ToolContributionSource> sourcesByName,
            List<String> diagnostics,
            boolean degraded) {

        public Snapshot {
            toolsByName = Collections.unmodifiableMap(new LinkedHashMap<>(toolsByName));
            sourcesByName = Collections.unmodifiableMap(new LinkedHashMap<>(sourcesByName));
            diagnostics = List.copyOf(diagnostics);
        }

        /**
         * Convenience constructor for snapshots built without source failures.
         *
         * @param version monotonic snapshot version
         * @param toolsByName tool index by name
         * @param sourcesByName source attribution by tool name
         * @param diagnostics non-fatal merge notes
         */
        public Snapshot(
                long version,
                Map<String, AgentTool> toolsByName,
                Map<String, ToolContributionSource> sourcesByName,
                List<String> diagnostics) {
            this(version, toolsByName, sourcesByName, diagnostics, false);
        }
    }
}
