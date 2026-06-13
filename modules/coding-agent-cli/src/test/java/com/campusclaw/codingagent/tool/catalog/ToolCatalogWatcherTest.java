/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolCatalogWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void invokesCallbackWhenWatchedDirectoryChanges() throws Exception {
        Path project = tempDir.resolve("project");
        Path userTools = tempDir.resolve("user-tools");
        Files.createDirectories(project.resolve(".campusclaw").resolve("tools"));
        Files.createDirectories(userTools);
        var latch = new CountDownLatch(1);
        var refreshes = new AtomicInteger();

        try (var watcher = ToolCatalogWatcher.start(new ToolSourceContext(project, userTools), () -> {
            refreshes.incrementAndGet();
            latch.countDown();
        })) {
            Files.writeString(project.resolve(".campusclaw").resolve("tools").resolve("hello.yaml"), "name: hello\n");

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(refreshes).hasValueGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void invokesCallbackWhenWatchedFileChanges() throws Exception {
        Path project = tempDir.resolve("project");
        Path userTools = tempDir.resolve("user-tools");
        Path settings = tempDir.resolve("settings").resolve("settings.json");
        Files.createDirectories(project.resolve(".campusclaw").resolve("tools"));
        Files.createDirectories(userTools);
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{}");
        var latch = new CountDownLatch(1);

        try (var watcher = ToolCatalogWatcher.start(
                new ToolSourceContext(project, userTools), List.of(settings), latch::countDown)) {
            Files.writeString(settings, "{\"tools\":{}}\n");

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
