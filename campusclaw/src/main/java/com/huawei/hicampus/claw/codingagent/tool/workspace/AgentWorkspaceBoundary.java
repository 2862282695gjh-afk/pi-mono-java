/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 当前受管 Agent 的不可变文件系统工作区边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public record AgentWorkspaceBoundary(String agentId, Path root, Path realRoot) {

    /**
     * 根据已准备完成的 Agent 根目录创建工作区边界。
     *
     * @param agentId 当前 Agent 标识
     * @param agentRoot 当前 Agent 根目录
     * @return 已校验的工作区边界
     */
    public static AgentWorkspaceBoundary create(String agentId, Path agentRoot) {
        if (agentId == null || agentId.isBlank() || agentRoot == null) {
            throw new IllegalArgumentException("agentId and agentRoot are required");
        }
        Path normalizedRoot = agentRoot.toAbsolutePath().normalize();
        validateRoot(normalizedRoot);
        try {
            return new AgentWorkspaceBoundary(agentId, normalizedRoot, normalizedRoot.toRealPath());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Agent workspace is unavailable", exception);
        }
    }

    private static void validateRoot(Path root) {
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Agent workspace must not be a symbolic link");
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(root)) {
            throw new IllegalArgumentException("Agent workspace is unavailable");
        }
    }
}
