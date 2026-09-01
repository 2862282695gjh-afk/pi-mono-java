/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ls;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.tool.ops.LocalLsOperations;
import com.huawei.hicampus.claw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.huawei.hicampus.claw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LsTool} 的精确契约和目录输出测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
class LsToolTest {

    @TempDir
    Path agentRoot;

    private LsTool tool;

    @BeforeEach
    void setUp() {
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", agentRoot);
        tool = new LsTool(new LocalLsOperations(), new WorkspacePathResolver(), boundary);
    }

    @Test
    void shouldPublishPascalCaseParallelContract() {
        assertThat(tool.name()).isEqualTo("Ls");
        assertThat(tool.description()).isEqualTo("List directory contents.");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("required").isMissingNode()).isTrue();
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void shouldListDotfilesAndDirectoriesWithoutFakeMetadata() throws Exception {
        Files.writeString(agentRoot.resolve("b.txt"), "b");
        Files.writeString(agentRoot.resolve(".env"), "secret");
        Files.createDirectory(agentRoot.resolve("A-dir"));

        var result = tool.execute("call", Map.of(), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo(".env\nA-dir/\nb.txt")
                .doesNotContain("rw-");
    }

    @Test
    void shouldApplyConfiguredLimit() throws Exception {
        Files.writeString(agentRoot.resolve("a"), "a");
        Files.writeString(agentRoot.resolve("b"), "b");

        var result = tool.execute("call", Map.of("limit", 1), null, null);

        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("a\n... (truncated)");
    }
}
