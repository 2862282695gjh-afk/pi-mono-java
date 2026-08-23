/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.ops;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;

/**
 * 不跟随符号链接的本地 Java 文件发现实现。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class LocalFindOperations implements FindOperations {

    private final WorkspacePathResolver pathResolver;

    public LocalFindOperations(WorkspacePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Override
    public FindResult find(
            AgentWorkspaceBoundary boundary,
            Path searchRoot,
            String pattern,
            int limit,
            CancellationToken cancellationToken)
            throws IOException {
        PathPatternMatcher matcher = PathPatternMatcher.glob(pattern);
        GitIgnoreRules ignores = GitIgnoreRules.load(boundary.realRoot(), cancellationToken);
        List<String> matches = new ArrayList<>();
        boolean[] truncated = {false};
        Files.walkFileTree(
                searchRoot,
                visitor(boundary, searchRoot, matcher, ignores, matches, limit, truncated, cancellationToken));
        matches.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        return new FindResult(List.copyOf(matches), truncated[0]);
    }

    private SimpleFileVisitor<Path> visitor(
            AgentWorkspaceBoundary boundary,
            Path searchRoot,
            PathPatternMatcher matcher,
            GitIgnoreRules ignores,
            List<String> matches,
            int limit,
            boolean[] truncated,
            CancellationToken cancellationToken) {
        return new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                checkCancellation(cancellationToken);
                if (directory.equals(searchRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink() || ignores.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return addMatch(boundary, searchRoot, directory, true, matcher, matches, limit, truncated);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                checkCancellation(cancellationToken);
                if (attributes.isSymbolicLink() || ignores.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                return addMatch(boundary, searchRoot, file, false, matcher, matches, limit, truncated);
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        };
    }

    private FileVisitResult addMatch(
            AgentWorkspaceBoundary boundary,
            Path searchRoot,
            Path path,
            boolean directory,
            PathPatternMatcher matcher,
            List<String> matches,
            int limit,
            boolean[] truncated) {
        Path validated = pathResolver.revalidateDiscoveredPath(boundary, path);
        String relative = normalize(searchRoot.relativize(validated));
        if (!matcher.matches(relative)) {
            return FileVisitResult.CONTINUE;
        }
        if (matches.size() >= limit) {
            truncated[0] = true;
            return FileVisitResult.TERMINATE;
        }
        matches.add(directory ? relative + "/" : relative);
        return FileVisitResult.CONTINUE;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void checkCancellation(CancellationToken token) {
        if (token != null && token.isCancelled()) {
            throw new CancellationException("Tool execution was cancelled");
        }
    }
}
