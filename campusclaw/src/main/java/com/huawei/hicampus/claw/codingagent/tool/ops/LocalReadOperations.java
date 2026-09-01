/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 提供 {@link ReadOperations} 的本地文件系统实现。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class LocalReadOperations implements ReadOperations {

    @Override
    public byte[] readFile(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }
}
