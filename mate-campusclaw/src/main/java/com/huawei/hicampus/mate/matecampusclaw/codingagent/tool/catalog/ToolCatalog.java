/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;

/**
 * Production entry point for resolving effective tools.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface ToolCatalog {

    ToolCatalogSnapshot snapshot();

    ToolCatalogSnapshot refresh();

    ToolCatalogSnapshot refresh(ToolRefreshRequest request);

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

    Runnable addChangeListener(ToolChangeListener listener);
}
