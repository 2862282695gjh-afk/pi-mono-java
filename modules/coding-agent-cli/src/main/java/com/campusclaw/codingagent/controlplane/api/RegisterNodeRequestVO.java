/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.controlplane.api;

import java.util.Set;

import com.campusclaw.agent.controlplane.domain.RuntimeCapability;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 数据面节点注册请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class RegisterNodeRequestVO {
    @NotBlank
    private String host;

    @Min(1)
    @Max(65_535)
    private int port;

    @NotBlank
    private String version;

    @NotNull
    private Set<RuntimeCapability> capabilities;
}
