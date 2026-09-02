/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.controlplane.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NodeMetrics} compact-constructor invariants — non-negative
 * counters and finite {@code cpuLoad}.
 *
 * @version [br_eCampusCore 26.0.0, 2026/06/18]
 * @since [br_eCampusCore 26.0.0]
 */
class NodeMetricsTest {

    @Test
    void rejectsNegativeAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(-1, 0, 0.0d, 0L));
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(0, -1, 0.0d, 0L));
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(0, 0, -0.1d, 0L));
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(0, 0, 0.0d, -1L));
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(0, 0, Double.NaN, 0L));
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(0, 0, Double.POSITIVE_INFINITY, 0L));
        assertThrows(IllegalArgumentException.class, () -> new NodeMetrics(0, 0, Double.NEGATIVE_INFINITY, 0L));
    }
}
