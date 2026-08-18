/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.nio.file.Path;
import java.util.List;

/**
 * Source of tool contributions for the catalog. Implementations are in-process
 * (Spring beans, in-tree extensions); this interface deliberately carries no
 * user/project directory or MCP semantics.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface ToolSource {

    /**
     * Loads the contributions this source currently offers.
     *
     * @param context refresh context carrying the working directory
     * @return additive contributions; never {@code null}
     */
    List<ToolContribution> load(Context context);

    /** Context passed to tool sources during catalog refresh. */
    record Context(Path cwd) {

        public Context {
            cwd = cwd != null ? cwd : Path.of(System.getProperty("user.dir"));
        }

        /**
         * Returns the context for the process working directory.
         *
         * @return default context
         */
        public static Context defaults() {
            return new Context(Path.of(System.getProperty("user.dir")));
        }
    }
}
