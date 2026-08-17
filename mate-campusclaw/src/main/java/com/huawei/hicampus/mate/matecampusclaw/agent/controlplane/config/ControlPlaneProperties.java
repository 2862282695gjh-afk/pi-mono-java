/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration root for the control plane process, bound from
 * {@code controlplane.*} properties in {@code application.yml}.
 *
 * <p>The container field ({@code heartbeat}) is a nested record and is never null —
 * Spring's {@code @ConfigurationProperties} will instantiate the defaults even if the
 * YAML omits the corresponding section.
 *
 * @param heartbeat heartbeat / liveness settings
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@ConfigurationProperties(prefix = "controlplane")
public record ControlPlaneProperties(Heartbeat heartbeat) {

    /**
     * Applies safe defaults for any nested record left null by binding.
     *
     * @param heartbeat heartbeat config; if null falls back to {@link Heartbeat#defaults()}
     */
    public ControlPlaneProperties {
        heartbeat = heartbeat == null ? Heartbeat.defaults() : heartbeat;
    }

    /**
     * Heartbeat tuning.
     *
     * @param ttl           a node missing heartbeats for longer than {@code ttl} is marked STALE
     * @param sweepInterval how often the cleanup task scans the registry
     * @param graceAfterStale how long a STALE node lingers before being removed entirely
     */
    public record Heartbeat(Duration ttl, Duration sweepInterval, Duration graceAfterStale) {

        private static final Duration DEFAULT_TTL = Duration.ofSeconds(30L);

        private static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(10L);

        private static final Duration DEFAULT_GRACE_AFTER_STALE = Duration.ofMinutes(5L);

        /**
         * Returns conservative defaults: 30 s TTL, 10 s sweep, 5 min STALE grace.
         *
         * @return a default heartbeat configuration
         */
        public static Heartbeat defaults() {
            return new Heartbeat(DEFAULT_TTL, DEFAULT_SWEEP_INTERVAL, DEFAULT_GRACE_AFTER_STALE);
        }

        /**
         * Compact constructor — null fields fall back to the inline default constants.
         *
         * @param ttl             see record component
         * @param sweepInterval   see record component
         * @param graceAfterStale see record component
         */
        public Heartbeat {
            ttl = ttl == null ? DEFAULT_TTL : ttl;
            sweepInterval = sweepInterval == null ? DEFAULT_SWEEP_INTERVAL : sweepInterval;
            graceAfterStale = graceAfterStale == null ? DEFAULT_GRACE_AFTER_STALE : graceAfterStale;
        }
    }
}
