/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.domain.RuntimeCapability;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime 节点调度请求。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
public class ScheduleRuntimeRequestVO {
    @NotNull
    private Set<RuntimeCapability> requiredCapabilities;

    private String preferredNodeId;
}
