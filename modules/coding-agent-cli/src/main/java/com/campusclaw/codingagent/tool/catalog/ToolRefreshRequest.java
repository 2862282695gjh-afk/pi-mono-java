/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.nio.file.Path;

/**
 * Request data for rebuilding a tool catalog snapshot.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolRefreshRequest(Path cwd) {

    public ToolSourceContext toSourceContext(ToolSourceContext previous) {
        var fallback = previous != null ? previous : ToolSourceContext.defaults();
        return new ToolSourceContext(cwd != null ? cwd : fallback.cwd(), fallback.userToolsDir());
    }
}
