/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability;

/**
 * Payload accepted by {@code POST /api/v1/nodes} when a data-plane node registers.
 *
 * <p>Validation lives in the compact constructor — the in-process control plane uses
 * webflux {@code RouterFunction} routing rather than {@code @RestController}, so
 * jakarta-validation annotations would need a separate validator wiring; the record
 * canonical-constructor invariants are the single source of truth.
 *
 * @param host         host advertised by the node
 * @param port         data-plane port
 * @param version      runtime version string
 * @param capabilities advertised capability tags
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record RegisterNodeRequest(String host, int port, String version, Set<RuntimeCapability> capabilities) {

    public RegisterNodeRequest {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        capabilities = Set.copyOf(capabilities);
    }
}
