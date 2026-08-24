/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import java.util.Set;

import com.campusclaw.agent.controlplane.domain.RuntimeCapability;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime 节点调度请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ScheduleRuntimeRequestVO {
    @NotNull
    private Set<RuntimeCapability> requiredCapabilities;

    private String preferredNodeId;
}
