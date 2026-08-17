/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.huawei.hicampus.mate.matecampusclaw.agent.util.LoggingUncaughtExceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches declarative tool directories and invokes a callback when they change.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ToolCatalogWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogWatcher.class);

    private final WatchService watchService;
    private final Runnable onChange;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    private ToolCatalogWatcher(WatchService watchService, Runnable onChange) {
        this.watchService = watchService;
        this.onChange = onChange;
        this.thread = new Thread(this::watchLoop, "tool-catalog-watcher");
        this.thread.setDaemon(true);
        this.thread.setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
        this.thread.start();
    }

    /**
     * Starts watching the user and project declarative tool directories.
     *
     * @param context the tool source context
     * @param onChange callback invoked after a filesystem change
     * @return the watcher handle
     * @throws IOException if the watch service cannot be created
     */
    public static ToolCatalogWatcher start(ToolSourceContext context, Runnable onChange) throws IOException {
        return start(context, List.of(), onChange);
    }

    /**
     * Starts watching declarative tool directories and additional files.
     *
     * @param context the tool source context
     * @param files files whose parent directories should be watched
     * @param onChange callback invoked after a filesystem change
     * @return the watcher handle
     * @throws IOException if the watch service cannot be created
     */
    public static ToolCatalogWatcher start(ToolSourceContext context, List<Path> files, Runnable onChange)
            throws IOException {
        var watchService = FileSystems.getDefault().newWatchService();
        var watched = new LinkedHashSet<Path>();
        watched.add(context.userToolsDir());
        watched.add(context.projectToolsDir());
        files.stream().map(Path::getParent).filter(parent -> parent != null).forEach(watched::add);
        registerDirectories(watchService, watched);
        return new ToolCatalogWatcher(watchService, onChange);
    }

    private static void registerDirectories(WatchService watchService, Set<Path> directories) throws IOException {
        for (Path directory : directories) {
            Files.createDirectories(directory);
            directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        }
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            }

            boolean changed = key.pollEvents().stream().anyMatch(event -> event.kind() != OVERFLOW);
            if (!key.reset()) {
                log.debug("Tool catalog watch key is no longer valid");
            }
            if (changed && running.get()) {
                onChange.run();
            }
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        watchService.close();
        thread.interrupt();
    }
}
