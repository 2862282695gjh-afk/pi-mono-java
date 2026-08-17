/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.tool;

import java.util.List;

import jakarta.annotation.Nullable;

/**
 * Resolves the effective tools for a runtime entry point.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ToolProvider {

    List<AgentTool> resolve(@Nullable List<String> allowedTools);
}
