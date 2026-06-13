/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarativeToolSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsProjectToolYamlAsAgentToolContribution() throws Exception {
        writeProjectTool(
                "hello.yaml",
                """
                apiVersion: campusclaw.dev/v1
                kind: Tool
                metadata:
                  name: hello_tool
                  label: Hello Tool
                spec:
                  description: Say hello.
                  inputSchema:
                    type: object
                    required: [name]
                    properties:
                      name:
                        type: string
                  execution:
                    type: process
                    command: ["/bin/sh", "-c", "printf 'hello %s' $TOOL_NAME"]
                    timeoutSeconds: 5
                    env:
                      TOOL_NAME: CampusClaw
                  merge:
                    strategy: ADD
                """);

        var source = new DeclarativeToolSource(new ToolDeclarationLoader());

        var contributions = source.load(context());

        assertThat(contributions).hasSize(1);
        var contribution = contributions.getFirst();
        assertThat(contribution.tool().name()).isEqualTo("hello_tool");
        assertThat(contribution.tool().label()).isEqualTo("Hello Tool");
        assertThat(contribution.tool().description()).isEqualTo("Say hello.");
        assertThat(contribution.source().layer()).isEqualTo("project");
        assertThat(contribution.priority()).isEqualTo(400);
        assertThat(contribution.mergeStrategy()).isEqualTo(ToolMergeStrategy.ADD);
    }

    @Test
    void processToolExecutesCommandAndReturnsStructuredDetails() throws Exception {
        writeProjectTool(
                "echo.yaml",
                """
                apiVersion: campusclaw.dev/v1
                kind: Tool
                metadata:
                  name: echo_tool
                  label: Echo Tool
                spec:
                  description: Echo input.
                  inputSchema:
                    type: object
                    properties: {}
                  execution:
                    type: process
                    command: ["/bin/sh", "-c", "printf 'out'; printf 'err' 1>&2"]
                    timeoutSeconds: 5
                  merge:
                    strategy: ADD
                """);
        var contribution = new DeclarativeToolSource(new ToolDeclarationLoader())
                .load(context())
                .getFirst();

        AgentToolResult result =
                contribution.tool().execute("call-1", Map.of(), new CancellationToken(), partial -> {});

        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("out");
        assertThat(result.details()).isEqualTo(Map.of("exitCode", 0, "stdout", "out", "stderr", "err"));
    }

    @Test
    void processToolThrowsWhenCommandTimesOut() throws Exception {
        writeProjectTool(
                "timeout.yaml",
                """
                apiVersion: campusclaw.dev/v1
                kind: Tool
                metadata:
                  name: slow_tool
                  label: Slow Tool
                spec:
                  description: Slow command.
                  inputSchema:
                    type: object
                    properties: {}
                  execution:
                    type: process
                    command: ["/bin/sh", "-c", "sleep 2"]
                    timeoutSeconds: 1
                  merge:
                    strategy: ADD
                """);
        var contribution = new DeclarativeToolSource(new ToolDeclarationLoader())
                .load(context())
                .getFirst();

        assertThatThrownBy(
                        () -> contribution.tool().execute("call-1", Map.of(), new CancellationToken(), partial -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void replaceMetadataIsPreservedFromYaml() throws Exception {
        writeProjectTool(
                "replace.yaml",
                """
                apiVersion: campusclaw.dev/v1
                kind: Tool
                metadata:
                  name: read
                  label: Project Read
                spec:
                  description: Project read replacement.
                  inputSchema:
                    type: object
                    properties: {}
                  execution:
                    type: process
                    command: ["/bin/sh", "-c", "printf replaced"]
                    timeoutSeconds: 5
                  merge:
                    strategy: REPLACE
                    replaces: read
                """);

        var contribution = new DeclarativeToolSource(new ToolDeclarationLoader())
                .load(context())
                .getFirst();

        assertThat(contribution.mergeStrategy()).isEqualTo(ToolMergeStrategy.REPLACE);
        assertThat(contribution.replaces()).isEqualTo("read");
        assertThat(contribution.priority()).isEqualTo(400);
    }

    @Test
    void catalogRefreshUsesNewCwdSnapshotWithoutMutatingOldSnapshotOnFailure() throws Exception {
        var validCwd = tempDir.resolve("valid");
        Files.createDirectories(validCwd);
        writeProjectTool(
                validCwd,
                "hello.yaml",
                """
                apiVersion: campusclaw.dev/v1
                kind: Tool
                metadata:
                  name: hello_tool
                  label: Hello Tool
                spec:
                  description: Say hello.
                  inputSchema:
                    type: object
                    properties: {}
                  execution:
                    type: process
                    command: ["/bin/sh", "-c", "printf hello"]
                    timeoutSeconds: 5
                  merge:
                    strategy: ADD
                """);
        var catalog = new DefaultToolCatalog(
                java.util.List.of(new DeclarativeToolSource(new ToolDeclarationLoader())),
                new ToolSourceContext(validCwd, tempDir.resolve("user-tools")));

        var oldSnapshot = catalog.snapshot();
        assertThat(oldSnapshot.toolsByName()).containsKey("hello_tool");

        var invalidCwd = tempDir.resolve("invalid");
        writeProjectTool(
                invalidCwd,
                "bad.yaml",
                """
                apiVersion: campusclaw.dev/v1
                kind: Tool
                metadata:
                  name: bad_tool
                """);

        var refreshed = catalog.refresh(new ToolRefreshRequest(invalidCwd));

        assertThat(refreshed.toolsByName()).containsKey("hello_tool");
        assertThat(refreshed.version()).isEqualTo(oldSnapshot.version());
        assertThat(refreshed.diagnostics()).isNotEmpty();
    }

    private ToolSourceContext context() {
        return new ToolSourceContext(tempDir, tempDir.resolve("user-tools"));
    }

    private void writeProjectTool(String name, String content) throws Exception {
        writeProjectTool(tempDir, name, content);
    }

    private void writeProjectTool(Path cwd, String name, String content) throws Exception {
        var toolsDir = cwd.resolve(".campusclaw").resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
