/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.codingagent.tool.workspace.AgentWorkspaceBoundary;

/**
 * 在 Agent 工作区内执行 glob 文件发现的只读操作抽象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface FindOperations {

    FindResult find(
            AgentWorkspaceBoundary boundary,
            Path searchRoot,
            String pattern,
            int limit,
            CancellationToken cancellationToken)
            throws IOException;

    @SuppressWarnings("checkstyle:top_class_comment")
    record FindResult(List<String> paths, boolean truncated) {}
}
