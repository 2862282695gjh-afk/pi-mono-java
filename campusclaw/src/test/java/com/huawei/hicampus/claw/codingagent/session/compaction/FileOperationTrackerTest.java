/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.StopReason;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.Usage;

import org.junit.jupiter.api.Test;

/**
 * 压缩核心文件追踪范围测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class FileOperationTrackerTest {
    @Test
    void tracksOnlyReadPaths() {
        AssistantMessage assistant = new AssistantMessage(
                List.of(
                        new ToolCall("read", "Read", Map.of("path", "src/App.java")),
                        new ToolCall("bash", "Bash", Map.of("command", "pwd")),
                        new ToolCall("edit", "Edit", Map.of("path", "ignored.java")),
                        new ToolCall("write", "Write", Map.of("path", "ignored.md"))),
                "api",
                "provider",
                "model",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                1L);

        assertThat(FileOperationTracker.filesRead(List.of(assistant))).containsExactly("src/App.java");
    }
}
