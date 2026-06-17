/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.util.List;

/**
 * Result from an MCP tools/call request.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record McpCallResult(List<McpContent> content, Object details) {

    public McpCallResult {
        content = List.copyOf(content != null ? content : List.of());
    }
}
