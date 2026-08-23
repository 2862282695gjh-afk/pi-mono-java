/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.find;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.LocalFindOperations;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FindTool} 的 glob、ignore 和输出契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
class FindToolTest {

    @TempDir
    Path agentRoot;

    private FindTool tool;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver();
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", agentRoot);
        tool = new FindTool(new LocalFindOperations(resolver), resolver, boundary);
    }

    @Test
    void shouldPublishPascalCaseParallelContract() {
        assertThat(tool.name()).isEqualTo("Find");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("properties").has("limit")).isTrue();
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void shouldFindRelativePathsAndHonorGitIgnore() throws Exception {
        Path source = Files.createDirectory(agentRoot.resolve("src"));
        Files.writeString(source.resolve("Main.java"), "class Main {}");
        Files.writeString(source.resolve("Ignored.java"), "class Ignored {}");
        Files.writeString(agentRoot.resolve(".gitignore"), "Ignored.java\n");

        var result = tool.execute("call", Map.of("pattern", "**/*.java"), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo("src/Main.java")
                .doesNotContain("Ignored.java");
    }

    @Test
    void shouldReturnStableEmptyText() throws Exception {
        var result = tool.execute("call", Map.of("pattern", "*.missing"), null, null);

        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("No files found.");
    }

    @Test
    void shouldStopBeforeWorkspaceTraversalWhenCancelled() {
        var signal = new CancellationToken();
        signal.cancel();

        assertThatThrownBy(() -> tool.execute("call", Map.of("pattern", "*"), signal, null))
                .isInstanceOf(CancellationException.class);
    }
}
