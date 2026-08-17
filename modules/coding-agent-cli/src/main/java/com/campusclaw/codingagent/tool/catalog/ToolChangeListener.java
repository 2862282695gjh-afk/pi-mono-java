/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

/**
 * Listener notified after a tool catalog snapshot changes.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface ToolChangeListener {

    void onToolsChanged(ToolCatalogSnapshot previous, ToolCatalogSnapshot current);
}
