/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeUtilsTest {

    @TempDir
    Path tempDir;

    @Nested
    class MoveAndCopy {

        @Test
        void copyRecursivelyReplicatesNestedTreeWithContent() throws Exception {
            Path source = tempDir.resolve("src-tree");
            Files.createDirectories(source.resolve("scripts"));
            Files.createDirectories(source.resolve("templates/nested"));
            Files.writeString(source.resolve("SKILL.md"), "manifest");
            Files.writeString(source.resolve("scripts/run.py"), "print('hi')");
            Files.writeString(source.resolve("templates/nested/deep.txt"), "deep value");

            Path target = tempDir.resolve("dst-tree");
            FileTreeUtils.copyRecursively(source, target);

            assertEquals("manifest", Files.readString(target.resolve("SKILL.md")));
            assertEquals("print('hi')", Files.readString(target.resolve("scripts/run.py")));
            assertEquals("deep value", Files.readString(target.resolve("templates/nested/deep.txt")));
        }

        @Test
        void relocatesDirectoryWhenAtomicRenameSucceeds() throws Exception {
            Path source = tempDir.resolve("mv-src");
            Files.createDirectories(source);
            Files.writeString(source.resolve("file.txt"), "payload");

            // Parent (tempDir) exists and is the same filesystem, so the rename(2) path is taken.
            Path target = tempDir.resolve("mv-dst");

            FileTreeUtils.moveDirectory(source, target);

            assertEquals("payload", Files.readString(target.resolve("file.txt")));
            assertFalse(Files.exists(source));
        }

        @Test
        void fallsBackToRecursiveCopyWhenRenameFails() throws Exception {
            // Force Files.move to fail by pointing at a target whose parent does not exist:
            // rename(2) raises NoSuchFileException (an IOException), exercising the SAME catch
            // branch as a cross-device EXDEV move. The fallback copy must create the missing
            // parents and replicate the non-empty directory.
            Path source = tempDir.resolve("fb-src");
            Files.createDirectories(source.resolve("sub"));
            Files.writeString(source.resolve("sub/data.txt"), "kept");
            Path target = tempDir.resolve("missing-parent/fb-dst");
            assertFalse(Files.exists(target.getParent()));

            FileTreeUtils.moveDirectory(source, target);

            assertEquals("kept", Files.readString(target.resolve("sub/data.txt")));
        }

        @Test
        void copyPreservesExecutableBit() throws Exception {
            Assumptions.assumeTrue(
                    FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "requires a POSIX filesystem");
            Path source = tempDir.resolve("exec-src");
            Files.createDirectories(source);
            Path script = source.resolve("run.sh");
            Files.writeString(script, "#!/bin/sh\necho hi\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

            Path target = tempDir.resolve("exec-dst");
            FileTreeUtils.copyRecursively(source, target);

            assertTrue(Files.isExecutable(target.resolve("run.sh")));
        }
    }

    @Nested
    class DeleteRecursively {

        @Test
        void deletesNestedDirectoryStructure() throws Exception {
            Path dir = tempDir.resolve("nested");
            Files.createDirectories(dir.resolve("a/b/c"));
            Files.writeString(dir.resolve("a/b/c/file.txt"), "content");
            Files.writeString(dir.resolve("a/file.txt"), "content");

            FileTreeUtils.deleteRecursively(dir);

            assertFalse(Files.exists(dir));
        }

        @Test
        void handlesNonExistentPath() {
            assertDoesNotThrow(() -> FileTreeUtils.deleteRecursively(tempDir.resolve("does-not-exist")));
        }

        @Test
        void handlesNull() {
            assertDoesNotThrow(() -> FileTreeUtils.deleteRecursively(null));
        }
    }
}
