/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

import com.campusclaw.agent.controlplane.domain.NodeInfo;
import com.campusclaw.agent.controlplane.domain.NodeMetrics;
import com.campusclaw.agent.controlplane.service.NodeRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 数据面节点注册、心跳、查询和注销的 Spring MVC 控制器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {
    private static final Logger log = LoggerFactory.getLogger(NodeController.class);

    private final NodeRegistry registry;

    public NodeController(NodeRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<NodeResponseVO> register(@Valid @RequestBody RegisterNodeRequestVO request) {
        NodeInfo node = registry.register(
                request.getHost(), request.getPort(), request.getVersion(), request.getCapabilities());
        log.info("REST register accepted: nodeId={}", node.nodeId());
        URI location = URI.create("/api/v1/nodes/" + node.nodeId());
        return ResponseEntity.created(location).body(new NodeResponseVO(node));
    }

    @PostMapping("/{nodeId}/heartbeat")
    public NodeResponseVO heartbeat(@PathVariable String nodeId, @Valid @RequestBody HeartbeatRequestVO request) {
        NodeMetrics metrics = new NodeMetrics(
                request.getActiveAgents(), request.getQueuedTasks(), request.getCpuLoad(), request.getMemoryUsedMb());
        return new NodeResponseVO(registry.heartbeat(nodeId, metrics));
    }

    @GetMapping
    public List<NodeResponseVO> list() {
        return registry.listAll().stream().map(NodeResponseVO::new).toList();
    }

    @GetMapping("/{nodeId}")
    public NodeResponseVO get(@PathVariable String nodeId) {
        NodeInfo node = registry.findNode(nodeId)
                .orElseThrow(() -> new NoSuchElementException("node not registered: " + nodeId));
        return new NodeResponseVO(node);
    }

    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> deregister(@PathVariable String nodeId) {
        return registry.deregister(nodeId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
