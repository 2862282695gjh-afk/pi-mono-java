/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Default MCP client factory.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class DefaultMcpClientFactory implements McpClientFactory {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public McpClient create(McpServerConfig config) {
        McpTransport transport =
                switch (config.transport()) {
                    case HTTP -> new HttpMcpTransport(mapper, config);
                    case STDIO -> new StdioMcpTransport(mapper, config);
                };
        return new JsonRpcMcpClient(mapper, transport);
    }
}
