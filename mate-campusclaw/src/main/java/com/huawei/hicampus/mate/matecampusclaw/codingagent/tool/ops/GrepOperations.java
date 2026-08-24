/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;

/**
 * 在 Agent 工作区内搜索文本内容的只读操作抽象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface GrepOperations {

    GrepResult grep(GrepRequest request, CancellationToken cancellationToken) throws IOException;

    @SuppressWarnings("checkstyle:top_class_comment")
    record GrepRequest(
            AgentWorkspaceBoundary boundary,
            Path searchPath,
            String pattern,
            String glob,
            boolean ignoreCase,
            boolean literal,
            int context,
            int limit) {}

    @SuppressWarnings("checkstyle:top_class_comment")
    record GrepResult(List<String> lines, boolean truncated) {}
}
