/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain;

import java.util.Set;

/**
 * Request payload for {@link com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service.RuntimeScheduler#schedule}.
 *
 * <p>The scheduler intersects {@code requiredCapabilities} against each candidate node's
 * advertised capability set and returns the first match according to the active load
 * balancing strategy. {@code preferredNodeId} provides session affinity when set.
 *
 * @param requiredCapabilities capabilities the chosen node must support
 * @param preferredNodeId      optional sticky node id; null means no affinity preference
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ScheduleRequest(Set<RuntimeCapability> requiredCapabilities, String preferredNodeId) {

    public ScheduleRequest {
        requiredCapabilities = Set.copyOf(requiredCapabilities == null ? Set.of() : requiredCapabilities);
    }
}
