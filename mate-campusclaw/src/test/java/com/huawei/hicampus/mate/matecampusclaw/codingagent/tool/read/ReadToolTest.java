/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import javax.imageio.ImageIO;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ImageContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.LocalReadOperations;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.WorkspaceAccessException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.WorkspacePathResolver;

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
        assertThat(tool.description()).isEqualTo("Read the contents of a file. Supports text files and images.");
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
    void shouldMarkDefaultLineTruncation() throws Exception {
        String content = "line\n".repeat(ReadTool.DEFAULT_MAX_LINES + 1);
        Files.writeString(agentRoot.resolve("large.txt"), content);

        var result = tool.execute("call", Map.of("path", "large.txt"), null, null);

        assertThat(((TextContent) result.content().get(0)).text()).contains("[Output truncated.");
    }

    @Test
    void byteTruncationShouldIncludeMarkerWithinPublishedBudget() throws Exception {
        Files.writeString(agentRoot.resolve("wide.txt"), "界".repeat(30_000));

        var result = tool.execute("call", Map.of("path", "wide.txt"), null, null);

        String output = ((TextContent) result.content().getFirst()).text();
        assertThat(output).contains("[Output truncated: first line exceeds the 50 KB limit.]");
        assertThat(output.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ReadTool.DEFAULT_MAX_BYTES);
    }

    @Test
    void shouldRejectUnsupportedBinaryFile() throws Exception {
        Files.write(agentRoot.resolve("data.bin"), new byte[] {1, 0, 2});

        assertThatThrownBy(() -> tool.execute("call", Map.of("path", "data.bin"), null, null))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Unsupported binary file");
    }

    @Test
    void shouldReturnSupportedImageContent() throws Exception {
        Path imagePath = agentRoot.resolve("picture.png");
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", imagePath.toFile());

        var result = tool.execute("call", Map.of("path", "picture.png"), null, null);

        assertThat(result.content().get(0)).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) result.content().get(0)).mimeType()).isEqualTo("image/png");
    }

    @Test
    void shouldDecodeWebpImage() throws Exception {
        byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        Files.write(agentRoot.resolve("picture.webp"), webp);

        var result = tool.execute("call", Map.of("path", "picture.webp"), null, null);

        assertThat(result.content().getFirst()).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) result.content().getFirst()).mimeType()).isEqualTo("image/webp");
    }

    @Test
    void shouldExplainWhenCurrentModelDoesNotSupportImages() throws Exception {
        Path imagePath = agentRoot.resolve("picture.png");
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", imagePath.toFile());
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", agentRoot);
        ReadTool textOnlyTool = new ReadTool(new LocalReadOperations(), new WorkspacePathResolver(), boundary, false);

        var result = textOnlyTool.execute("call", Map.of("path", "picture.png"), null, null);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().getFirst()).isInstanceOf(ImageContent.class);
        assertThat(((TextContent) result.content().getLast()).text())
                .isEqualTo("The current model does not declare image input support.");
    }
}
