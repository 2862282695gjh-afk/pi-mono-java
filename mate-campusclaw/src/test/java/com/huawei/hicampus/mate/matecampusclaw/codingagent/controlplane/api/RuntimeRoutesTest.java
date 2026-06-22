/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service.NodeRegistry;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service.RuntimeScheduler;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.error.ControlPlaneExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * webflux integration tests for {@link RuntimeRoutes} using {@link WebTestClient} bound
 * directly to the assembled router function — no {@code @SpringBootTest} required.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeRoutesTest {

    private NodeRegistry registry;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry(new ControlPlaneProperties(null), Clock.systemUTC());
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        ControlPlaneExceptionHandler errorFilter = new ControlPlaneExceptionHandler(Clock.systemUTC());
        RouterFunction<ServerResponse> routes =
                new RuntimeRoutes().runtimeControlPlaneRoutes(registry, scheduler, errorFilter);
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void capabilitiesEndpointReturnsUnionAcrossActiveNodes() {
        registerNode("10.0.1.1", 9001, Set.of("MODEL_OPENAI", "TOOL_BASH"));
        registerNode("10.0.1.2", 9002, Set.of("MODEL_ANTHROPIC"));

        client.get()
                .uri("/api/v1/runtimes/capabilities")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Set.class)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .containsExactlyInAnyOrder("MODEL_OPENAI", "TOOL_BASH", "MODEL_ANTHROPIC"));
    }

    @Test
    void runtimesEndpointFlattensActiveNodesIntoView() {
        registerNode("10.0.2.1", 9101, Set.of("MODEL_OPENAI"));

        client.get()
                .uri("/api/v1/runtimes")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(RuntimeRoutes.RuntimeView.class)
                .hasSize(1)
                .consumeWith(result -> {
                    RuntimeRoutes.RuntimeView view = result.getResponseBody().get(0);
                    assertThat(view.host()).isEqualTo("10.0.2.1");
                    assertThat(view.port()).isEqualTo(9101);
                });
    }

    @Test
    void schedulePicksAnEligibleNode() {
        String firstId = registerNode("10.0.3.1", 9201, Set.of("MODEL_OPENAI", "TOOL_BASH"));
        registerNode("10.0.3.2", 9202, Set.of("MODEL_ANTHROPIC"));

        client.post()
                .uri("/api/v1/runtimes/schedule")
                .bodyValue(Map.of("requiredCapabilities", Set.of("MODEL_OPENAI")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.nodeId")
                .isEqualTo(firstId)
                .jsonPath("$.reason")
                .value(reason -> assertThat((String) reason).isIn("round-robin", "affinity"));
    }

    @Test
    void scheduleAffinityHonoredWhenPreferredNodeIdMatches() {
        String first = registerNode("10.0.4.1", 9301, Set.of("TOOL_BASH"));
        registerNode("10.0.4.2", 9302, Set.of("TOOL_BASH"));

        client.post()
                .uri("/api/v1/runtimes/schedule")
                .bodyValue(Map.of("requiredCapabilities", Set.of("TOOL_BASH"), "preferredNodeId", first))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.nodeId")
                .isEqualTo(first)
                .jsonPath("$.reason")
                .isEqualTo("affinity");
    }

    @Test
    void scheduleReturns404WhenNoEligibleNode() {
        registerNode("10.0.5.1", 9401, Set.of("TOOL_BASH"));

        client.post()
                .uri("/api/v1/runtimes/schedule")
                .bodyValue(Map.of("requiredCapabilities", Set.of("MODEL_GOOGLE")))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404);
    }

    @Test
    void scheduleWithoutBodyReturns400() {
        client.post()
                .uri("/api/v1/runtimes/schedule")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400)
                .jsonPath("$.message")
                .value(message -> assertThat((String) message).contains("request body is required"));
    }

    @Test
    void scheduleWithNullCapabilitiesReturns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("requiredCapabilities", null);

        client.post()
                .uri("/api/v1/runtimes/schedule")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400)
                .jsonPath("$.message")
                .value(message -> assertThat((String) message).contains("requiredCapabilities must not be null"));
    }

    @Test
    void scheduleWithInvalidCapabilityReturns400() {
        client.post()
                .uri("/api/v1/runtimes/schedule")
                .bodyValue(Map.of("requiredCapabilities", Set.of("NOT_A_CAPABILITY")))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400);
    }

    private String registerNode(String host, int port, Set<String> capabilities) {
        return registry.register(host, port, "1.0.0", toCapabilities(capabilities))
                .nodeId();
    }

    private Set<com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability> toCapabilities(Set<String> names) {
        Set<com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability> out = new java.util.HashSet<>();
        for (String n : names) {
            out.add(com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability.valueOf(n));
        }
        return out;
    }
}
