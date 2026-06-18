/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import com.campusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.service.NodeRegistry;
import com.campusclaw.codingagent.controlplane.error.ControlPlaneExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * webflux integration tests for {@link NodeRoutes} using {@link WebTestClient} bound
 * directly to the router function — no {@code @SpringBootTest} required, the bean
 * graph is assembled by hand for fast, hermetic tests.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class NodeRoutesTest {

    private NodeRegistry registry;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry(new ControlPlaneProperties(null), Clock.systemUTC());
        ControlPlaneExceptionHandler errorFilter = new ControlPlaneExceptionHandler(Clock.systemUTC());
        RouterFunction<ServerResponse> routes = new NodeRoutes().nodeControlPlaneRoutes(registry, errorFilter);
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void registerThenListReturnsTheNode() {
        String nodeId = registerAndExtractId(Map.of(
                "host",
                "10.0.0.10",
                "port",
                9001,
                "version",
                "1.0.0",
                "capabilities",
                Set.of("MODEL_OPENAI", "TOOL_BASH")));

        client.get()
                .uri("/api/v1/nodes")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(NodeInfo.class)
                .hasSize(1)
                .consumeWith(result ->
                        assertThat(result.getResponseBody().get(0).nodeId()).isEqualTo(nodeId));
    }

    @Test
    void heartbeatRefreshesMetricsForRegisteredNode() {
        String nodeId = registerAndExtractId(Map.of(
                "host", "10.0.0.11", "port", 9002, "version", "1.0.0", "capabilities", Set.of("MODEL_ANTHROPIC")));

        client.post()
                .uri("/api/v1/nodes/{nodeId}/heartbeat", nodeId)
                .bodyValue(Map.of(
                        "activeAgents", 2,
                        "queuedTasks", 1,
                        "cpuLoad", 0.7,
                        "memoryUsedMb", 512))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.metrics.activeAgents")
                .isEqualTo(2)
                .jsonPath("$.metrics.queuedTasks")
                .isEqualTo(1)
                .jsonPath("$.metrics.memoryUsedMb")
                .isEqualTo(512);
    }

    @Test
    void heartbeatForUnknownNodeReturns404() {
        client.post()
                .uri("/api/v1/nodes/node-missing/heartbeat")
                .bodyValue(Map.of("activeAgents", 0, "queuedTasks", 0, "cpuLoad", 0.0, "memoryUsedMb", 0))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404)
                .jsonPath("$.message")
                .value(msg -> assertThat((String) msg).contains("node-missing"));
    }

    @Test
    void registerWithInvalidPortReturns400() {
        client.post()
                .uri("/api/v1/nodes")
                .bodyValue(Map.of("host", "10.0.0.12", "port", 0, "version", "1.0.0", "capabilities", Set.of()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400)
                .jsonPath("$.message")
                .value(msg -> assertThat(((String) msg).toLowerCase(java.util.Locale.ROOT))
                        .contains("port"));
    }

    @Test
    void deregisterRemovesNode() {
        String nodeId = registerAndExtractId(
                Map.of("host", "10.0.0.13", "port", 9003, "version", "1.0.0", "capabilities", Set.of()));

        client.delete()
                .uri("/api/v1/nodes/{nodeId}", nodeId)
                .exchange()
                .expectStatus()
                .isNoContent();

        client.delete()
                .uri("/api/v1/nodes/{nodeId}", nodeId)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private String registerAndExtractId(Map<String, Object> registerBody) {
        NodeInfo created = client.post()
                .uri("/api/v1/nodes")
                .bodyValue(registerBody)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(NodeInfo.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.nodeId()).startsWith("node-");
        return created.nodeId();
    }
}
