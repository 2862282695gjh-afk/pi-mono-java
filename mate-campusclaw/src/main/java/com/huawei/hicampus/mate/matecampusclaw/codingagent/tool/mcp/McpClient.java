/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mcp;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;

/**
 * Client for MCP tool discovery and invocation.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface McpClient extends AutoCloseable {

    List<McpToolDefinition> listTools();

    McpCallResult callTool(String name, Map<String, Object> arguments, CancellationToken signal);

    @Override
    void close();
}
