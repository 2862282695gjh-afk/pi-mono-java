/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

/**
 * Describes where a tool contribution came from. Only in-process layers exist
 * today: tools registered as Spring beans and tools contributed by in-tree
 * extensions. User/project directory scanning and MCP servers are not tool
 * sources in this codebase (see ADR-0011).
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record ToolContributionSource(String layer, String sourceId) {

    /**
     * Creates a system-layer source, e.g. Spring-registered tools.
     *
     * @param sourceId source identifier for diagnostics
     * @return the source
     */
    public static ToolContributionSource system(String sourceId) {
        return new ToolContributionSource("system", sourceId);
    }

    /**
     * Creates an extension-layer source for an in-tree extension.
     *
     * @param extensionId the extension identifier
     * @return the source
     */
    public static ToolContributionSource extension(String extensionId) {
        return new ToolContributionSource("extension", "extension:" + extensionId);
    }
}
