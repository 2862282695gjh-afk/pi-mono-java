/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.ops;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;

/**
 * 不调用 shell 且不跟随符号链接的本地 Java 文本搜索实现。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class LocalGrepOperations implements GrepOperations {

    static final int MAX_LINE_CHARACTERS = 500;

    private final WorkspacePathResolver pathResolver;

    public LocalGrepOperations(WorkspacePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Override
    public GrepResult grep(GrepRequest request, CancellationToken cancellationToken) throws IOException {
        Pattern pattern = compilePattern(request);
        GitIgnoreRules ignores = GitIgnoreRules.load(request.boundary().realRoot(), cancellationToken);
        PathPatternMatcher globMatcher =
                request.glob() == null || request.glob().isBlank() ? null : PathPatternMatcher.glob(request.glob());
        SearchAccumulator accumulator = new SearchAccumulator(request.limit());
        if (Files.isRegularFile(request.searchPath())) {
            searchFile(request, request.searchPath(), pattern, accumulator, cancellationToken);
        } else {
            walkFiles(request, pattern, globMatcher, ignores, accumulator, cancellationToken);
        }
        return new GrepResult(List.copyOf(accumulator.lines), accumulator.truncated);
    }

    private void walkFiles(
            GrepRequest request,
            Pattern pattern,
            PathPatternMatcher globMatcher,
            GitIgnoreRules ignores,
            SearchAccumulator accumulator,
            CancellationToken cancellationToken)
            throws IOException {
        Files.walkFileTree(request.searchPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                checkCancellation(cancellationToken);
                if (!directory.equals(request.searchPath()) && ignores.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return accumulator.truncated ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                checkCancellation(cancellationToken);
                if (attributes.isSymbolicLink() || ignores.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                if (matchesGlob(request.searchPath(), file, globMatcher)) {
                    searchFile(request, file, pattern, accumulator, cancellationToken);
                }
                return accumulator.truncated ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void searchFile(
            GrepRequest request,
            Path file,
            Pattern pattern,
            SearchAccumulator accumulator,
            CancellationToken cancellationToken) {
        Path validated = pathResolver.revalidateDiscoveredPath(request.boundary(), file);
        String displayPath = normalize(request.boundary().realRoot().relativize(validated));
        List<String> fileOutput = new ArrayList<>();
        int initialMatches = accumulator.matches;
        try (BufferedReader reader = Files.newBufferedReader(validated, StandardCharsets.UTF_8)) {
            scanLines(request, reader, displayPath, pattern, fileOutput, accumulator, cancellationToken);
            accumulator.lines.addAll(fileOutput);
        } catch (CharacterCodingException exception) {
            accumulator.matches = initialMatches;
        } catch (IOException exception) {
            accumulator.matches = initialMatches;
        }
    }

    private static void scanLines(
            GrepRequest request,
            BufferedReader reader,
            String path,
            Pattern pattern,
            List<String> output,
            SearchAccumulator accumulator,
            CancellationToken cancellationToken)
            throws IOException {
        Deque<NumberedLine> previous = new ArrayDeque<>();
        Map<LineIdentity, Integer> outputIndexes = new HashMap<>();
        int afterRemaining = 0;
        String line = reader.readLine();
        int lineNumber = 0;
        while (line != null && !accumulator.truncated) {
            checkCancellation(cancellationToken);
            lineNumber++;
            if (line.indexOf('\0') >= 0) {
                throw new CharacterCodingException();
            }
            boolean matched = pattern.matcher(line).find();
            if (matched) {
                if (accumulator.matches >= request.limit()) {
                    accumulator.truncated = true;
                    break;
                }
                appendBeforeContext(output, outputIndexes, path, previous);
                appendOutput(output, outputIndexes, path, lineNumber, line, true);
                accumulator.matches++;
                afterRemaining = request.context();
            } else if (afterRemaining > 0) {
                appendOutput(output, outputIndexes, path, lineNumber, line, false);
                afterRemaining--;
            }
            remember(previous, new NumberedLine(lineNumber, line), request.context());
            line = reader.readLine();
        }
    }

    private static void appendBeforeContext(
            List<String> output, Map<LineIdentity, Integer> outputIndexes, String path, Deque<NumberedLine> previous) {
        for (NumberedLine line : previous) {
            appendOutput(output, outputIndexes, path, line.number(), line.text(), false);
        }
    }

    private static void appendOutput(
            List<String> output,
            Map<LineIdentity, Integer> outputIndexes,
            String path,
            int lineNumber,
            String line,
            boolean match) {
        LineIdentity identity = new LineIdentity(path, lineNumber);
        Integer existingIndex = outputIndexes.get(identity);
        if (existingIndex == null) {
            outputIndexes.put(identity, output.size());
            output.add(format(path, lineNumber, line, match));
        } else if (match) {
            output.set(existingIndex, format(path, lineNumber, line, true));
        }
    }

    private static void remember(Deque<NumberedLine> previous, NumberedLine line, int context) {
        if (context == 0) {
            return;
        }
        previous.addLast(line);
        while (previous.size() > context) {
            previous.removeFirst();
        }
    }

    private static Pattern compilePattern(GrepRequest request) {
        int flags = request.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        String expression = request.literal() ? Pattern.quote(request.pattern()) : request.pattern();
        try {
            return Pattern.compile(expression, flags);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("Invalid regular expression", exception);
        }
    }

    private static boolean matchesGlob(Path searchRoot, Path file, PathPatternMatcher matcher) {
        return matcher == null || matcher.matches(normalize(searchRoot.relativize(file)));
    }

    private static String format(String path, int lineNumber, String line, boolean match) {
        String content = line.length() > MAX_LINE_CHARACTERS
                ? line.substring(0, MAX_LINE_CHARACTERS) + "... [line truncated]"
                : line;
        String separator = match ? ":" : "-";
        return path + separator + lineNumber + separator + content;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void checkCancellation(CancellationToken token) {
        if (token != null && token.isCancelled()) {
            throw new CancellationException("Tool execution was cancelled");
        }
    }

    private record NumberedLine(int number, String text) {}

    private record LineIdentity(String path, int number) {}

    private static final class SearchAccumulator {
        private final List<String> lines = new ArrayList<>();
        private final int limit;
        private int matches;
        private boolean truncated;

        private SearchAccumulator(int limit) {
            this.limit = limit;
        }
    }
}
