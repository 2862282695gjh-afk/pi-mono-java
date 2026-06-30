/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

class HttpMcpTransportTest {

    @Test
    void requestCanBeCancelledThroughCancellationToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                Thread.sleep(10_000L);
                byte[] response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(response);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var signal = new CancellationToken();
        var config = config("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");

        try (var transport = new HttpMcpTransport(new ObjectMapper(), config);
                var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(
                    () -> transport.request("tools/list", new ObjectMapper().createObjectNode(), signal));

            signal.cancel();

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("cancelled");
        } finally {
            server.stop(0);
        }
    }

    private McpServerConfig config(String url) {
        return new McpServerConfig(
                "http",
                true,
                McpServerConfig.Transport.HTTP,
                List.of(),
                url,
                Map.of(),
                McpServerConfig.Trust.TRUSTED,
                null,
                McpServerConfig.ExposeNames.PREFIXED,
                1,
                30);
    }
}
