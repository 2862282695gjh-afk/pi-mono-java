/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.nio.file.Path;

/**
 * Request data for rebuilding a tool catalog snapshot.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record ToolRefreshRequest(Path cwd) {

    /**
     * Derives the source context for this request.
     *
     * @param previous context to fall back to when the request carries no cwd
     * @return the effective source context
     */
    public ToolSource.Context toSourceContext(ToolSource.Context previous) {
        var fallback = previous != null ? previous : ToolSource.Context.defaults();
        return new ToolSource.Context(cwd != null ? cwd : fallback.cwd());
    }
}
