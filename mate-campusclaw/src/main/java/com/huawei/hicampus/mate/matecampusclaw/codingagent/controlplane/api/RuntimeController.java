/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeInfo;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.NodeStatus;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.ScheduleRequest;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service.NodeRegistry;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service.RuntimeScheduler;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 活动 Runtime 聚合视图和调度决策的 Spring MVC 控制器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@RestController
@RequestMapping("/api/v1/runtimes")
public class RuntimeController {
    private final NodeRegistry registry;

    private final RuntimeScheduler scheduler;

    public RuntimeController(NodeRegistry registry, RuntimeScheduler scheduler) {
        this.registry = registry;
        this.scheduler = scheduler;
    }

    @GetMapping
    public List<RuntimeNodeResponseVO> list() {
        return activeNodes().stream().map(RuntimeNodeResponseVO::new).toList();
    }

    @GetMapping("/capabilities")
    public Set<RuntimeCapability> capabilities() {
        Set<RuntimeCapability> capabilities = new HashSet<>();
        activeNodes().forEach(node -> capabilities.addAll(node.capabilities()));
        return Set.copyOf(capabilities);
    }

    @PostMapping("/schedule")
    public ScheduleRuntimeResponseVO schedule(@Valid @RequestBody ScheduleRuntimeRequestVO request) {
        ScheduleRequest command = new ScheduleRequest(
                request.getRequiredCapabilities(), request.getPreferredNodeId());
        return new ScheduleRuntimeResponseVO(scheduler.schedule(command));
    }

    private List<NodeInfo> activeNodes() {
        return registry.listAll().stream()
                .filter(node -> node.status() == NodeStatus.ACTIVE)
                .toList();
    }
}
