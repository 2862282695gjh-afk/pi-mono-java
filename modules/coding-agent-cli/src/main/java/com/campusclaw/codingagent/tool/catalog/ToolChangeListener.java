/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

/**
 * Listener notified after a tool catalog snapshot changes.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@FunctionalInterface
public interface ToolChangeListener {

    void onToolsChanged(ToolCatalogSnapshot previous, ToolCatalogSnapshot current);
}
