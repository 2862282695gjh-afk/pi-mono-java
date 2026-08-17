/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.List;

/**
 * Source of tool contributions for the catalog.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface ToolSource {

    List<ToolContribution> load(ToolSourceContext context);
}
