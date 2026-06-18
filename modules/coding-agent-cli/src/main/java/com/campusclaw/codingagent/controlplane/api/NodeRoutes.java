/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import java.util.NoSuchElementException;

import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.domain.NodeMetrics;
import com.campusclaw.agent.controlplane.service.NodeRegistry;
import com.campusclaw.codingagent.controlplane.error.ControlPlaneExceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * webflux {@link RouterFunction} bean exposing the data-plane node lifecycle endpoints.
 *
 * <p>The control plane HTTP surface lives in {@code coding-agent-cli} alongside the chat
 * / skill / settings handlers because both share the reactor-netty server bootstrapped by
 * {@code ServerMode}. We deliberately do not use {@code @RestController} since
 * {@code application.yml} sets {@code spring.main.web-application-type: none} — autoconf
 * won't pick controllers up; the routes have to be wired explicitly.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST   /api/v1/nodes}                          — register, 201 Created</li>
 *   <li>{@code POST   /api/v1/nodes/{id}/heartbeat}           — heartbeat, 200 OK</li>
 *   <li>{@code GET    /api/v1/nodes}                          — list, 200 OK</li>
 *   <li>{@code GET    /api/v1/nodes/{id}}                     — get one, 200 OK / 404</li>
 *   <li>{@code DELETE /api/v1/nodes/{id}}                     — deregister, 204 / 404</li>
 * </ul>
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Configuration
public class NodeRoutes {

    private static final Logger log = LoggerFactory.getLogger(NodeRoutes.class);

    /**
     * Builds the node-lifecycle {@link RouterFunction}. Registered as a Spring bean so
     * {@code ServerMode} can compose it into the top-level reactor-netty handler chain.
     *
     * @param registry node registry service from agent-core
     * @param errorFilter shared exception-to-HTTP translator attached as a filter so
     *     {@code IllegalArgumentException} / {@code NoSuchElementException} from
     *     {@code NodeRegistry} surface as 400 / 404 with structured JSON bodies
     * @return router function covering the five node endpoints
     */
    @Bean
    public RouterFunction<ServerResponse> nodeControlPlaneRoutes(
            NodeRegistry registry, ControlPlaneExceptionHandler errorFilter) {
        return route(POST("/api/v1/nodes"), req -> req.bodyToMono(RegisterNodeRequest.class)
                        .flatMap(body -> {
                            NodeInfo created =
                                    registry.register(body.host(), body.port(), body.version(), body.capabilities());
                            log.info("REST register accepted: nodeId={}", created.nodeId());
                            URI location = URI.create("/api/v1/nodes/" + created.nodeId());
                            return ServerResponse.created(location).bodyValue(created);
                        }))
                .andRoute(POST("/api/v1/nodes/{nodeId}/heartbeat"), req -> {
                    String nodeId = req.pathVariable("nodeId");
                    return req.bodyToMono(HeartbeatRequest.class).flatMap(body -> {
                        NodeMetrics metrics = new NodeMetrics(
                                body.activeAgents(), body.queuedTasks(), body.cpuLoad(), body.memoryUsedMb());
                        NodeInfo updated = registry.heartbeat(nodeId, metrics);
                        return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(updated);
                    });
                })
                .andRoute(GET("/api/v1/nodes"), req -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(registry.listAll()))
                .andRoute(GET("/api/v1/nodes/{nodeId}"), req -> {
                    String nodeId = req.pathVariable("nodeId");
                    NodeInfo info = registry.findNode(nodeId)
                            .orElseThrow(() -> new NoSuchElementException("node not registered: " + nodeId));
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(info);
                })
                .andRoute(DELETE("/api/v1/nodes/{nodeId}"), req -> {
                    String nodeId = req.pathVariable("nodeId");
                    boolean removed = registry.deregister(nodeId);
                    if (removed) {
                        return ServerResponse.noContent().build();
                    }
                    return ServerResponse.notFound().build();
                })
                .filter(errorFilter);
    }
}
