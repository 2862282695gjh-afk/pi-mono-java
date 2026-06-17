/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.tool;

import java.util.List;

import jakarta.annotation.Nullable;

/**
 * Resolves the effective tools for a runtime entry point.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/13]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface ToolProvider {

    List<AgentTool> resolve(@Nullable List<String> allowedTools);
}
