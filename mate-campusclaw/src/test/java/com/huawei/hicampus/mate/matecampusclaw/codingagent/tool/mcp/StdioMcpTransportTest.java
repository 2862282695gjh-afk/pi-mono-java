/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
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

    @Test
    void requestCanBeCancelledThroughCancellationToken() throws Exception {
        var mapper = new ObjectMapper();
        var signal = new CancellationToken();
        var config = new McpServerConfig(
                "cancel",
                true,
                McpServerConfig.Transport.STDIO,
                List.of(
                        "/bin/sh",
                        "-c",
                        "while read line; do sleep 10; echo '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}'; done"),
                null,
                Map.of(),
                McpServerConfig.Trust.TRUSTED,
                null,
                McpServerConfig.ExposeNames.PREFIXED,
                1,
                30);

        try (var transport = new StdioMcpTransport(mapper, config);
                var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> transport.request("tools/list", mapper.createObjectNode(), signal));

            signal.cancel();

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("cancelled");
        }
    }
}
