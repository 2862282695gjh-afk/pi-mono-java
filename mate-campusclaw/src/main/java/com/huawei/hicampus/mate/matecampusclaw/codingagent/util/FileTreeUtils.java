/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-tree operations: moving a directory with a cross-filesystem fallback, recursive copy, and
 * best-effort recursive delete. These are generic filesystem utilities, independent of any caller.
 *
 * @version [br_eCampusCore 26.0.0, 2026/06/23]
 * @since [br_eCampusCore 26.0.0]
 */
public final class FileTreeUtils {

    private static final Logger log = LoggerFactory.getLogger(FileTreeUtils.class);

    private FileTreeUtils() {}

    /**
     * Moves a directory, falling back to a recursive copy when an atomic rename is not possible.
     *
     * <p>{@link Files#move(Path, Path, java.nio.file.CopyOption...)} performs a single
     * {@code rename(2)} on Unix, which fails with {@code EXDEV} when source and target live on
     * different filesystems (for example {@code /tmp} mounted as a separate tmpfs, or a service
     * running with {@code PrivateTmp=true}). In that case the JDK refuses to move a non-empty
     * directory across devices and throws {@link java.nio.file.DirectoryNotEmptyException}; we then
     * copy the tree instead. The source is left in place for the caller to clean up.
     *
     * @param source the directory to move
     * @param target the destination path, which must not already exist
     * @throws IOException if the directory can be neither moved nor copied
     */
    public static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (IOException renameFailed) {
            log.debug("Atomic move {} -> {} failed, falling back to recursive copy", source, target, renameFailed);
            copyRecursively(source, target);
        }
    }

    /**
     * Recursively copies the file tree rooted at {@code source} to {@code target}.
     *
     * @param source the directory to copy from
     * @param target the directory to copy into (created if absent)
     * @throws IOException if any entry cannot be copied
     */
    public static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Recursively deletes a directory tree, best-effort. A {@code null} or non-existent path is a
     * no-op; failures are logged rather than propagated.
     *
     * @param dir the directory tree to delete
     */
    public static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", dir, e);
        }
    }
}
