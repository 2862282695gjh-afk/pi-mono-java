/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

/**
 * Describes where a tool contribution came from.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolContributionSource(String layer, String sourceId) {

    public static ToolContributionSource system(String sourceId) {
        return new ToolContributionSource("system", sourceId);
    }

    public static ToolContributionSource extension(String extensionId) {
        return new ToolContributionSource("extension", "extension:" + extensionId);
    }

    public static ToolContributionSource user(String sourceId) {
        return new ToolContributionSource("user", sourceId);
    }

    public static ToolContributionSource project(String sourceId) {
        return new ToolContributionSource("project", sourceId);
    }

    public static ToolContributionSource mcp(String serverName) {
        return new ToolContributionSource("mcp", "mcp:" + serverName);
    }
}
