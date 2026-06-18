/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.controlplane.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweeper that demotes expired nodes from ACTIVE to STALE and removes nodes
 * past their grace period.
 *
 * <p>Runs at a fixed delay configured by {@code controlplane.heartbeat.sweep-interval}.
 * The actual logic lives in {@link NodeRegistry#sweep()}; this class is a thin Spring
 * scheduler hook so the registry remains framework-light.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    private final NodeRegistry registry;

    /**
     * Spring constructor.
     *
     * @param registry node registry whose sweep method is invoked
     */
    public HealthCheckScheduler(NodeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Triggers a sweep cycle. The fixed delay is read from the {@code controlplane}
     * configuration root via Spring property placeholder; defaults to 10 s when unset.
     */
    @Scheduled(fixedDelayString = "${controlplane.heartbeat.sweep-interval:PT10S}")
    public void sweep() {
        int transitions = registry.sweep();
        if (transitions > 0) {
            log.info("registry sweep transitions: count={}", transitions);
        }
    }
}
