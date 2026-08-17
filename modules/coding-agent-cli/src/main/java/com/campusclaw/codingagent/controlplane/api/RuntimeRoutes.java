/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.domain.NodeStatus;
import com.campusclaw.agent.controlplane.domain.RuntimeCapability;
import com.campusclaw.agent.controlplane.domain.ScheduleDecision;
import com.campusclaw.agent.controlplane.domain.ScheduleRequest;
import com.campusclaw.agent.controlplane.service.NodeRegistry;
import com.campusclaw.agent.controlplane.service.RuntimeScheduler;
import com.campusclaw.codingagent.controlplane.error.ControlPlaneExceptionHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

/**
 * webflux {@link RouterFunction} bean exposing the runtime-aggregation view across all
 * registered nodes plus the scheduling decision endpoint.
 *
 * <p>Mirrors {@link NodeRoutes} in style — webflux {@code RouterFunction} instead of
 * {@code @RestController}, attached to the main reactor-netty server via
 * {@code ServerMode.setExtraRoutes}. Exceptions are routed through
 * {@link ControlPlaneExceptionHandler} so a {@code NoSuchElementException} from
 * {@code RuntimeScheduler} (no eligible node) surfaces as a structured 404.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/runtimes}            — flattened per-node view, 200 OK</li>
 *   <li>{@code GET  /api/v1/runtimes/capabilities} — union of capabilities, 200 OK</li>
 *   <li>{@code POST /api/v1/runtimes/schedule}  — pick a node, 200 OK / 404</li>
 * </ul>
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Configuration
public class RuntimeRoutes {

    /**
     * Builds the runtime-aggregation {@link RouterFunction}. Registered as a Spring bean
     * so {@code ServerMode} can compose it into the top-level reactor-netty handler chain.
     *
     * @param registry  node registry service from agent-core
     * @param scheduler runtime scheduler service from agent-core
     * @param errorFilter shared exception-to-HTTP translator from MR-B
     * @return router function covering the three runtime endpoints
     */
    @Bean
    public RouterFunction<ServerResponse> runtimeControlPlaneRoutes(
            NodeRegistry registry, RuntimeScheduler scheduler, ControlPlaneExceptionHandler errorFilter) {
        return route(GET("/api/v1/runtimes"), req -> {
                    List<RuntimeView> views = registry.listAll().stream()
                            .filter(node -> node.status() == NodeStatus.ACTIVE)
                            .map(RuntimeView::from)
                            .toList();
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(views);
                })
                .andRoute(GET("/api/v1/runtimes/capabilities"), req -> {
                    Set<RuntimeCapability> union = new HashSet<>();
                    for (NodeInfo node : registry.listAll()) {
                        if (node.status() == NodeStatus.ACTIVE) {
                            union.addAll(node.capabilities());
                        }
                    }
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Set.copyOf(union));
                })
                .andRoute(POST("/api/v1/runtimes/schedule"), req -> req.bodyToMono(ScheduleRequestBody.class)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("request body is required")))
                        .flatMap(body -> {
                            ScheduleRequest domain =
                                    new ScheduleRequest(body.requiredCapabilities(), body.preferredNodeId());
                            ScheduleDecision decision = scheduler.schedule(domain);
                            return ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(decision);
                        }))
                .filter(errorFilter);
    }

    /**
     * Compact JSON view of a runtime node, suitable for the management UI.
     *
     * @param nodeId       node identifier
     * @param host         advertised host
     * @param port         advertised port
     * @param version      runtime version string
     * @param capabilities advertised capability tags
     * @param activeAgents currently running Agent count from latest heartbeat
     */
    public record RuntimeView(
            String nodeId,
            String host,
            int port,
            String version,
            Set<RuntimeCapability> capabilities,
            int activeAgents) {

        static RuntimeView from(NodeInfo node) {
            return new RuntimeView(
                    node.nodeId(),
                    node.host(),
                    node.port(),
                    node.version(),
                    node.capabilities(),
                    node.metrics().activeAgents());
        }
    }

    /**
     * Body for the {@code /schedule} endpoint. Validation lives in the compact
     * constructor — see {@link com.campusclaw.codingagent.controlplane.api.RegisterNodeRequest}
     * for the rationale.
     *
     * @param requiredCapabilities capabilities the chosen node must support; never null
     * @param preferredNodeId      optional sticky node id; null means no affinity preference
     */
    public record ScheduleRequestBody(Set<RuntimeCapability> requiredCapabilities, String preferredNodeId) {

        public ScheduleRequestBody {
            if (requiredCapabilities == null) {
                throw new IllegalArgumentException("requiredCapabilities must not be null");
            }
            requiredCapabilities = Set.copyOf(requiredCapabilities);
        }
    }
}
