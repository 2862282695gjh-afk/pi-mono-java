/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.nio.file.Path;

import com.campusclaw.ai.utils.CampusClawHome;
import com.campusclaw.codingagent.config.AppPaths;

/**
 * Context passed to tool sources during catalog refresh.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolSourceContext(Path cwd, Path userToolsDir) {

    public ToolSourceContext {
        cwd = cwd != null ? cwd : Path.of(System.getProperty("user.dir"));
        userToolsDir =
                userToolsDir != null ? userToolsDir : CampusClawHome.baseDir().resolve("tools");
    }

    public static ToolSourceContext defaults() {
        return new ToolSourceContext(
                Path.of(System.getProperty("user.dir")),
                CampusClawHome.baseDir().resolve("tools"));
    }

    public Path projectToolsDir() {
        return cwd.resolve(AppPaths.PROJECT_CONFIG_SUBDIR).resolve("tools");
    }
}
