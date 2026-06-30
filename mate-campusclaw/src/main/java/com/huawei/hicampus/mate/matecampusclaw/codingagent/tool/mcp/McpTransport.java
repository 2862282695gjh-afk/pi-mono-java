/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mcp;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC transport used by MCP clients.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface McpTransport extends AutoCloseable {

    JsonNode request(String method, JsonNode params);

    default void notify(String method, JsonNode params) {
        // Transports that support JSON-RPC notifications override this method.
    }

    default JsonNode request(String method, JsonNode params, CancellationToken signal) {
        if (signal != null && signal.isCancelled()) {
            throw new McpException("MCP request cancelled");
        }
        return request(method, params);
    }

    @Override
    void close();
}
