/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 不跟随且不暴露符号链接的本地目录列举实现。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class LocalLsOperations implements LsOperations {

    @Override
    public List<LsEntry> list(Path directory) throws IOException {
        List<LsEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                BasicFileAttributes attrs =
                        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink()) {
                    continue;
                }
                String type;
                if (attrs.isDirectory()) {
                    type = "directory";
                } else {
                    type = "file";
                }
                entries.add(new LsEntry(
                        path.getFileName().toString(),
                        type,
                        attrs.size(),
                        attrs.lastModifiedTime().toInstant()));
            }
        }
        return entries;
    }
}
