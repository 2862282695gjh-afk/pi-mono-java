/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.nio.file.Path;

import com.campusclaw.codingagent.settings.Settings;

/**
 * Request data for rebuilding a tool catalog snapshot.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolRefreshRequest(Path cwd, Settings.ToolsSettings toolsSettings) {

    public ToolRefreshRequest(Path cwd) {
        this(cwd, null);
    }

    public ToolSourceContext toSourceContext(ToolSourceContext previous) {
        var fallback = previous != null ? previous : ToolSourceContext.defaults();
        return new ToolSourceContext(
                cwd != null ? cwd : fallback.cwd(),
                fallback.userToolsDir(),
                settingOrFallback(
                        toolsSettings != null ? toolsSettings.allowUserTools() : null, fallback.userToolsEnabled()),
                settingOrFallback(
                        toolsSettings != null ? toolsSettings.allowProjectTools() : null,
                        fallback.projectToolsEnabled()),
                settingOrFallback(
                        toolsSettings != null ? toolsSettings.allowToolReplacement() : null,
                        fallback.replacementEnabled()),
                settingOrFallback(toolsSettings != null ? toolsSettings.mcpEnabled() : null, fallback.mcpEnabled()));
    }

    private static boolean settingOrFallback(Boolean setting, boolean fallback) {
        return setting != null ? setting : fallback;
    }
}
