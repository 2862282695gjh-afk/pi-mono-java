/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import com.campusclaw.agent.tool.AgentTool;

/**
 * Marker for tools that orchestrate the session lifecycle instead of performing
 * business work. Control tools are discovered through the regular catalog chain,
 * are exempt from the remote Agent tool allow list, but remain subject to the
 * local {@link ToolSelection} visibility policy.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ControlTool extends AgentTool {}
