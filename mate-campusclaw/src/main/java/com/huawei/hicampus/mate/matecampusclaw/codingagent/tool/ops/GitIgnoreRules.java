/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;

/**
 * 为只读文件工具提供常用 Git ignore 规则的工作区内匹配能力。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
final class GitIgnoreRules {

    private final Path workspaceRoot;
    private final List<IgnoreRule> rules;

    private GitIgnoreRules(Path workspaceRoot, List<IgnoreRule> rules) {
        this.workspaceRoot = workspaceRoot;
        this.rules = rules;
    }

    static GitIgnoreRules load(Path workspaceRoot, CancellationToken cancellationToken) {
        List<Path> ignoreFiles = new ArrayList<>(findIgnoreFiles(workspaceRoot, cancellationToken));
        ignoreFiles.sort(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString));
        List<IgnoreRule> rules = new ArrayList<>();
        for (Path ignoreFile : ignoreFiles) {
            checkCancellation(cancellationToken);
            rules.addAll(readRules(workspaceRoot, ignoreFile, cancellationToken));
        }
        return new GitIgnoreRules(workspaceRoot, List.copyOf(rules));
    }

    boolean isIgnored(Path path, boolean directory) {
        String workspaceRelative = normalize(workspaceRoot.relativize(path));
        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.matches(workspaceRelative, directory)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private static List<Path> findIgnoreFiles(Path workspaceRoot, CancellationToken cancellationToken) {
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            return paths.peek(path -> checkCancellation(cancellationToken))
                    .filter(path -> ".gitignore".equals(path.getFileName().toString()))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !hasSymbolicLink(workspaceRoot, path))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static List<IgnoreRule> readRules(
            Path workspaceRoot, Path ignoreFile, CancellationToken cancellationToken) {
        try {
            String base = normalize(workspaceRoot.relativize(ignoreFile.getParent()));
            List<IgnoreRule> rules = new ArrayList<>();
            for (String line : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
                checkCancellation(cancellationToken);
                IgnoreRule rule = parseRule(base, line);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            return rules;
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static IgnoreRule parseRule(String base, String source) {
        String line = source.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        boolean negated = line.startsWith("!");
        String value = negated ? line.substring(1) : line;
        boolean directoryOnly = value.endsWith("/");
        value = directoryOnly ? value.substring(0, value.length() - 1) : value;
        boolean anchored = value.startsWith("/");
        value = anchored ? value.substring(1) : value;
        return new IgnoreRule(base, value, negated, directoryOnly, anchored);
    }

    private static boolean hasSymbolicLink(Path root, Path path) {
        Path current = root;
        for (Path component : root.relativize(path)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void checkCancellation(CancellationToken token) {
        if (token != null && token.isCancelled()) {
            throw new CancellationException("Tool execution was cancelled");
        }
    }

    private record IgnoreRule(String base, String glob, boolean negated, boolean directoryOnly, boolean anchored) {

        private boolean matches(String workspaceRelative, boolean directory) {
            String relative = relativeToBase(workspaceRelative);
            if (relative == null || directoryOnly && !directory && !isDescendant(relative)) {
                return false;
            }
            if (anchored || glob.indexOf('/') >= 0) {
                return matchesPathOrDescendant(relative, glob);
            }
            return matchesAnySegment(relative);
        }

        private String relativeToBase(String path) {
            if (base.isEmpty()) {
                return path;
            }
            if (path.equals(base)) {
                return "";
            }
            return path.startsWith(base + "/") ? path.substring(base.length() + 1) : null;
        }

        private boolean matchesAnySegment(String path) {
            PathPatternMatcher matcher = PathPatternMatcher.glob(glob);
            String[] segments = path.split("/");
            for (String segment : segments) {
                if (matcher.matches(segment)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesPathOrDescendant(String path, String value) {
            PathPatternMatcher matcher = PathPatternMatcher.rootedGlob(value);
            if (matcher.matches(path)) {
                return true;
            }
            for (int index = path.lastIndexOf('/'); index > 0; index = path.lastIndexOf('/', index - 1)) {
                if (matcher.matches(path.substring(0, index))) {
                    return true;
                }
            }
            return false;
        }

        private boolean isDescendant(String path) {
            return path.indexOf('/') >= 0;
        }
    }
}
