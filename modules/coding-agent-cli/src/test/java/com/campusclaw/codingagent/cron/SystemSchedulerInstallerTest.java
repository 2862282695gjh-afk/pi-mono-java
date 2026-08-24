/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.cron;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemSchedulerInstallerTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsUnsupportedOperatingSystemOperations() {
        var installer = new SystemSchedulerInstaller(
                Path.of("campusclaw.sh"), tempDir.resolve("cron.plist"), SystemSchedulerInstaller.Os.UNSUPPORTED);

        assertThatThrownBy(() -> installer.install(60))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("supports macOS and Linux only");
        assertThatThrownBy(installer::uninstall)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("supports macOS and Linux only");
        assertThatThrownBy(installer::status)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("supports macOS and Linux only");
    }
}
