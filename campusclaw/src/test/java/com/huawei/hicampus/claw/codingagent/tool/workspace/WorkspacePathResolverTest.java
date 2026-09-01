/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspacePathResolver} 的工作区隔离测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
class WorkspacePathResolverTest {

    @TempDir
    Path temporaryDirectory;

    private final WorkspacePathResolver resolver = new WorkspacePathResolver();

    @Test
    void shouldResolveRelativeAndContainedAbsolutePaths() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("agent-a"));
        Path file = Files.writeString(root.resolve("SYSTEM.md"), "system");
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", root);

        assertThat(resolver.resolveFile(boundary, "SYSTEM.md")).isEqualTo(file.toRealPath());
        assertThat(resolver.resolveFile(boundary, file.toString())).isEqualTo(file.toRealPath());
        assertThat(resolver.resolveDirectory(boundary, null)).isEqualTo(root.toRealPath());
    }

    @Test
    void shouldRejectSiblingAgentAndTraversalWithoutLeakingPhysicalPath() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("agent-a"));
        Path sibling = Files.createDirectory(temporaryDirectory.resolve("agent-b"));
        Path secret = Files.writeString(sibling.resolve("secret.txt"), "secret");
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", root);

        assertSanitizedRejection(boundary, "../agent-b/secret.txt", sibling);
        assertSanitizedRejection(boundary, secret.toString(), sibling);
    }

    @Test
    void shouldRejectSymbolicLinkInAnyPathComponent() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("agent-a"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("linked"), outside);
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", root);

        assertThatThrownBy(() -> resolver.resolveFile(boundary, "linked/secret.txt"))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Symbolic links are not allowed");
    }

    @Test
    void shouldRejectSymbolicLinkAsWorkspaceRoot() throws Exception {
        Path realRoot = Files.createDirectory(temporaryDirectory.resolve("real-agent"));
        Path linkedRoot = Files.createSymbolicLink(temporaryDirectory.resolve("agent-a"), realRoot);

        assertThatThrownBy(() -> AgentWorkspaceBoundary.create("agent-a", linkedRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent workspace must not be a symbolic link");
    }

    @Test
    void shouldRejectMissingAndWrongTypePaths() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("agent-a"));
        Files.writeString(root.resolve("file.txt"), "value");
        AgentWorkspaceBoundary boundary = AgentWorkspaceBoundary.create("agent-a", root);

        assertThatThrownBy(() -> resolver.resolveFile(boundary, "missing.txt"))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Path does not exist");
        assertThatThrownBy(() -> resolver.resolveDirectory(boundary, "file.txt"))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Path is not a readable directory");
    }

    private void assertSanitizedRejection(AgentWorkspaceBoundary boundary, String input, Path hiddenPath) {
        assertThatThrownBy(() -> resolver.resolveFile(boundary, input))
                .isInstanceOf(WorkspaceAccessException.class)
                .hasMessage("Path is outside the current Agent workspace")
                .hasMessageNotContaining(hiddenPath.toString())
                .hasMessageNotContaining("agent-b");
    }
}
