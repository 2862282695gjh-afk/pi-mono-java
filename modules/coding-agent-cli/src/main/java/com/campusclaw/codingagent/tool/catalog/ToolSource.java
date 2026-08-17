/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.nio.file.Path;
import java.util.List;

import com.campusclaw.ai.utils.CampusClawHome;
import com.campusclaw.codingagent.config.AppPaths;

/**
 * Source of tool contributions for the catalog.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface ToolSource {

    List<ToolContribution> load(Context context);

    /** Context passed to tool sources during catalog refresh. */
    record Context(
            Path cwd,
            Path userToolsDir,
            boolean userToolsEnabled,
            boolean projectToolsEnabled,
            boolean replacementEnabled,
            boolean mcpEnabled) {

        public Context {
            cwd = cwd != null ? cwd : Path.of(System.getProperty("user.dir"));
            userToolsDir = userToolsDir != null
                    ? userToolsDir
                    : CampusClawHome.baseDir().resolve("tools");
        }

        public Context(Path cwd, Path userToolsDir) {
            this(cwd, userToolsDir, true, true, true, true);
        }

        public static Context defaults() {
            return new Context(
                    Path.of(System.getProperty("user.dir")),
                    CampusClawHome.baseDir().resolve("tools"),
                    true,
                    true,
                    true,
                    true);
        }

        public Path projectToolsDir() {
            return cwd.resolve(AppPaths.PROJECT_CONFIG_SUBDIR).resolve("tools");
        }
    }
}
