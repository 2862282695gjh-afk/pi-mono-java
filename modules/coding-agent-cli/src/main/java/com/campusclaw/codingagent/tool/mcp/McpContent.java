/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

/**
 * MCP content block.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record McpContent(String type, String text) {

    public static McpContent text(String text) {
        return new McpContent("text", text);
    }
}
