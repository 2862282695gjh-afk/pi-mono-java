/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;

/**
 * Production entry point for resolving effective tools.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface ToolCatalog {

    ToolCatalogSnapshot snapshot();

    ToolCatalogSnapshot refresh();

    ToolCatalogSnapshot refresh(ToolRefreshRequest request);

    List<AgentTool> resolve(ToolSelection selection);
}
