/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.grep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.LocalGrepOperations;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GrepTool} 的模式、上下文、ignore 和输出契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
class GrepToolTest {

    @TempDir
    Path agentRoot;

    private GrepTool tool;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver();
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", agentRoot);
        tool = new GrepTool(new LocalGrepOperations(resolver), resolver, boundary);
    }

    @Test
    void shouldPublishPascalCaseParallelContract() {
        assertThat(tool.name()).isEqualTo("Grep");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("properties").has("literal")).isTrue();
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void shouldSearchLiteralCaseInsensitiveWithContext() throws Exception {
        Files.writeString(agentRoot.resolve("notes.txt"), "before\nNeedle[1]\nafter\n");

        var result = tool.execute(
                "call", Map.of("pattern", "needle[1]", "literal", true, "ignoreCase", true, "context", 1), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .contains("notes.txt-1-before")
                .contains("notes.txt:2:Needle[1]")
                .contains("notes.txt-3-after");
    }

    @Test
    void shouldHonorGlobAndGitIgnore() throws Exception {
        Files.writeString(agentRoot.resolve("keep.txt"), "target");
        Files.writeString(agentRoot.resolve("skip.log"), "target");
        Files.writeString(agentRoot.resolve("ignored.txt"), "target");
        Files.writeString(agentRoot.resolve(".gitignore"), "ignored.txt\n");

        var result = tool.execute("call", Map.of("pattern", "target", "glob", "*.txt"), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo("keep.txt:1:target")
                .doesNotContain("skip.log", "ignored.txt");
    }

    @Test
    void shouldKeepNestedFileForRootAnchoredGitIgnorePattern() throws Exception {
        Files.writeString(agentRoot.resolve("ignored.txt"), "target");
        Path source = Files.createDirectory(agentRoot.resolve("src"));
        Files.writeString(source.resolve("kept.txt"), "target");
        Files.writeString(agentRoot.resolve(".gitignore"), "/*.txt\n");

        var result = tool.execute("call", Map.of("pattern", "target", "glob", "*.txt"), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo("src/kept.txt:1:target")
                .doesNotContain("ignored.txt");
    }

    @Test
    void shouldNotMarkExactLimitAsTruncated() throws Exception {
        Files.writeString(agentRoot.resolve("notes.txt"), "target\ntarget\ncontext\n");

        var result = tool.execute("call", Map.of("pattern", "target", "limit", 2, "context", 1), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .contains("notes.txt:1:target", "notes.txt:2:target", "notes.txt-3-context")
                .doesNotContain("... (truncated)");
    }

    @Test
    void shouldMergeOverlappingContextByPathAndLine() throws Exception {
        Files.writeString(agentRoot.resolve("notes.txt"), "target\ntarget\ncontext\n");

        var result = tool.execute("call", Map.of("pattern", "target", "limit", 2, "context", 1), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo("notes.txt:1:target\nnotes.txt:2:target\nnotes.txt-3-context");
    }

    @Test
    void shouldMarkOutputWhenAnotherMatchExistsBeyondLimit() throws Exception {
        Files.writeString(agentRoot.resolve("notes.txt"), "target\ntarget\ntarget\n");

        var result = tool.execute("call", Map.of("pattern", "target", "limit", 2), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .contains("notes.txt:1:target", "notes.txt:2:target", "... (truncated)")
                .doesNotContain("notes.txt:3:target");
    }

    @Test
    void shouldStopBeforeWorkspaceTraversalWhenCancelled() {
        var signal = new CancellationToken();
        signal.cancel();

        assertThatThrownBy(() -> tool.execute("call", Map.of("pattern", "target"), signal, null))
                .isInstanceOf(CancellationException.class);
    }
}
