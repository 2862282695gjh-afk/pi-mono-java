/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * 在当前 Agent 根目录内安全解析和复核文件系统路径。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class WorkspacePathResolver {

    /**
     * 解析可读普通文件。
     *
     * @param boundary 工作区边界
     * @param input 模型输入路径
     * @return 已校验路径
     * @throws WorkspaceAccessException 路径不可读或越过当前 Agent 工作区时抛出
     */
    public Path resolveFile(AgentWorkspaceBoundary boundary, String input) {
        Path path = resolveExisting(boundary, input, false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new WorkspaceAccessException("Path is not a readable file");
        }
        return path;
    }

    /**
     * 解析可读目录，空输入表示工作区根目录。
     *
     * @param boundary 工作区边界
     * @param input 模型输入路径
     * @return 已校验路径
     * @throws WorkspaceAccessException 路径不可读或越过当前 Agent 工作区时抛出
     */
    public Path resolveDirectory(AgentWorkspaceBoundary boundary, String input) {
        Path path = resolveExisting(boundary, input, true);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new WorkspaceAccessException("Path is not a readable directory");
        }
        return path;
    }

    /**
     * 解析可读文件或目录，空输入表示工作区根目录。
     *
     * @param boundary 工作区边界
     * @param input 模型输入路径
     * @return 已校验路径
     * @throws WorkspaceAccessException 路径不可读或越过当前 Agent 工作区时抛出
     */
    public Path resolveFileOrDirectory(AgentWorkspaceBoundary boundary, String input) {
        Path path = resolveExisting(boundary, input, true);
        boolean readableType = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        if (!readableType || !Files.isReadable(path)) {
            throw new WorkspaceAccessException("Path is not readable");
        }
        return path;
    }

    /**
     * 对遍历过程中发现的路径再次执行边界和符号链接校验。
     *
     * @param boundary 工作区边界
     * @param path 已发现路径
     * @return 已复核路径
     * @throws WorkspaceAccessException 路径不存在、包含符号链接或越过当前 Agent 工作区时抛出
     */
    public Path revalidateDiscoveredPath(AgentWorkspaceBoundary boundary, Path path) {
        requireBoundary(boundary);
        Path candidate = path.toAbsolutePath().normalize();
        Path lexicalRoot = discoveredRoot(boundary, candidate);
        ensureNoSymbolicLink(lexicalRoot, candidate);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceAccessException("Path does not exist");
        }
        return ensureRealBoundary(boundary, candidate);
    }

    private Path resolveExisting(AgentWorkspaceBoundary boundary, String input, boolean emptyMeansRoot) {
        requireBoundary(boundary);
        Path candidate = parseCandidate(boundary, input, emptyMeansRoot);
        ensureLexicalBoundary(boundary, candidate);
        ensureNoSymbolicLink(boundary.root(), candidate);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceAccessException("Path does not exist");
        }
        return ensureRealBoundary(boundary, candidate);
    }

    private static Path parseCandidate(AgentWorkspaceBoundary boundary, String input, boolean emptyMeansRoot) {
        if (input == null || input.isBlank()) {
            if (emptyMeansRoot) {
                return boundary.root();
            }
            throw new WorkspaceAccessException("Path is required");
        }
        if (input.indexOf('\0') >= 0) {
            throw new WorkspaceAccessException("Path is invalid");
        }
        try {
            Path supplied = Path.of(input);
            return (supplied.isAbsolute() ? supplied : boundary.root().resolve(supplied))
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException exception) {
            throw new WorkspaceAccessException("Path is invalid", exception);
        }
    }

    private static void requireBoundary(AgentWorkspaceBoundary boundary) {
        if (boundary == null) {
            throw new WorkspaceAccessException("Agent workspace is unavailable");
        }
    }

    private static void ensureLexicalBoundary(AgentWorkspaceBoundary boundary, Path candidate) {
        if (!candidate.startsWith(boundary.root())) {
            throw new WorkspaceAccessException("Path is outside the current Agent workspace");
        }
    }

    private static Path discoveredRoot(AgentWorkspaceBoundary boundary, Path candidate) {
        if (candidate.startsWith(boundary.realRoot())) {
            return boundary.realRoot();
        }
        if (candidate.startsWith(boundary.root())) {
            return boundary.root();
        }
        throw new WorkspaceAccessException("Path is outside the current Agent workspace");
    }

    private static void ensureNoSymbolicLink(Path root, Path candidate) {
        Path relative = root.relativize(candidate);
        Path current = root;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new WorkspaceAccessException("Symbolic links are not allowed");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
    }

    private static Path ensureRealBoundary(AgentWorkspaceBoundary boundary, Path candidate) {
        try {
            Path realPath = candidate.toRealPath();
            if (!realPath.startsWith(boundary.realRoot())) {
                throw new WorkspaceAccessException("Path is outside the current Agent workspace");
            }
            return realPath;
        } catch (IOException exception) {
            throw new WorkspaceAccessException("Path is unavailable", exception);
        }
    }
}
