/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.tool.ops.LocalReadOperations;
import com.huawei.hicampus.claw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.huawei.hicampus.claw.codingagent.tool.workspace.WorkspaceAccessException;
import com.huawei.hicampus.claw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ReadTool} 的精确契约和工作区隔离测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
class ReadToolTest {

    @TempDir
    Path agentRoot;

    private ReadTool tool;

    @BeforeEach
    void setUp() {
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", agentRoot);
        tool = new ReadTool(new LocalReadOperations(), new WorkspacePathResolver(), boundary);
    }

    @Test
    void shouldPublishPascalCaseParallelContract() {
        assertThat(tool.name()).isEqualTo("Read");
        assertThat(tool.description()).isEqualTo("Read the contents of a UTF-8 text file.");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
        assertThat(tool.parameters().path("required").get(0).asText()).isEqualTo("path");
    }

    @Test
    void shouldReturnRawTextAndApplyOffsetAndLimit() throws Exception {
        Files.writeString(agentRoot.resolve("notes.txt"), "one\ntwo\nthree\nfour");

        var result = tool.execute("call", Map.of("path", "notes.txt", "offset", 2, "limit", 2), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .startsWith("two\nthree")
                .contains("[Output truncated.");
    }

    @Test
    void shouldNotTreatTrailingNewlineAsAdditionalLine() throws Exception {
        String content = "one\ntwo\n";
        Files.writeString(agentRoot.resolve("terminated.txt"), content);

        var result = tool.execute("call", Map.of("path", "terminated.txt", "offset", 1, "limit", 2), null, null);

        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo(content);
        assertThat(((ReadToolDetails) result.details()).truncation()).isNull();
    }

    @Test
    void shouldReturnEmptyFileWithoutTruncation() throws Exception {
        Files.writeString(agentRoot.resolve("empty.txt"), "");

        var result = tool.execute("call", Map.of("path", "empty.txt"), null, null);

        assertThat(((TextContent) result.content().getFirst()).text()).isEmpty();
        assertThat(((ReadToolDetails) result.details()).truncation()).isNull();
    }

    @Test
    void shouldMarkDefaultLineTruncation() throws Exception {
        String content = "line\n".repeat(ReadTool.DEFAULT_MAX_LINES + 1);
        Files.writeString(agentRoot.resolve("large.txt"), content);

        var result = tool.execute("call", Map.of("path", "large.txt"), null, null);

        assertThat(((TextContent) result.content().get(0)).text()).contains("[Output truncated.");
    }

    @Test
    void byteTruncationShouldIncludeMarkerWithinPublishedBudget() throws Exception {
        Files.writeString(agentRoot.resolve("wide.txt"), "界".repeat(30_000) + "\n");

        var result = tool.execute("call", Map.of("path", "wide.txt"), null, null);

        String output = ((TextContent) result.content().getFirst()).text();
        var details = (ReadToolDetails) result.details();
        assertThat(output).contains("[Output truncated: first line exceeds the 50 KB limit.]");
        assertThat(output.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ReadTool.DEFAULT_MAX_BYTES);
        assertThat(details.truncation()).isNotNull();
        assertThat(details.truncation().totalLines()).isEqualTo(1);
    }

    @Test
    void shouldRejectUnsupportedBinaryFile() throws Exception {
        Files.write(agentRoot.resolve("data.bin"), new byte[] {1, 0, 2});

        assertThatThrownBy(() -> tool.execute("call", Map.of("path", "data.bin"), null, null))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Unsupported binary file");
    }

    @Test
    void shouldRejectImageFileAsBinary() throws Exception {
        byte[] pngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        Files.write(agentRoot.resolve("picture.png"), pngHeader);

        assertThatThrownBy(() -> tool.execute("call", Map.of("path", "picture.png"), null, null))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Unsupported binary file");
    }
}
