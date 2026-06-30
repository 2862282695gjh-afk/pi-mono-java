/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultAgentsFileWhenMissing() throws Exception {
        Path agentDir = tempDir.resolve("agent");
        Files.createDirectories(agentDir);

        AppPaths.ensureDefaultAgentsFile(agentDir);

        Path agentsFile = agentDir.resolve(AppPaths.AGENTS_FILENAME);
        assertThat(agentsFile).exists();
        assertThat(Files.readString(agentsFile)).isEqualTo(AppPaths.DEFAULT_AGENTS_CONTENT);
    }

    @Test
    void keepsExistingAgentsFile() throws Exception {
        Path agentDir = tempDir.resolve("agent");
        Files.createDirectories(agentDir);
        Path agentsFile = agentDir.resolve(AppPaths.AGENTS_FILENAME);
        Files.writeString(agentsFile, "custom content\n");

        AppPaths.ensureDefaultAgentsFile(agentDir);

        assertThat(Files.readString(agentsFile)).isEqualTo("custom content\n");
    }
}
