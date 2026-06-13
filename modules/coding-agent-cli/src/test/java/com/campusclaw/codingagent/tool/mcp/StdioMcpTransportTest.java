/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class StdioMcpTransportTest {

    @Test
    void requestUsesConfiguredCallTimeout() {
        var config = new McpServerConfig(
                "slow",
                true,
                McpServerConfig.Transport.STDIO,
                List.of(
                        "/bin/sh",
                        "-c",
                        "while read line; do sleep 2; echo '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}'; done"),
                null,
                Map.of(),
                McpServerConfig.Trust.TRUSTED,
                null,
                McpServerConfig.ExposeNames.PREFIXED,
                1,
                1);

        try (var transport = new StdioMcpTransport(new ObjectMapper(), config)) {
            assertThatThrownBy(() -> transport.request("tools/list", new ObjectMapper().createObjectNode()))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("timed out");
        }
    }
}
