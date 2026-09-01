/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ops;

import java.util.regex.Pattern;

/**
 * 将工具使用的跨平台 glob 表达式转换为路径匹配器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
final class PathPatternMatcher {

    private final Pattern pattern;

    private PathPatternMatcher(Pattern pattern) {
        this.pattern = pattern;
    }

    static PathPatternMatcher glob(String glob) {
        String normalized = normalize(glob);
        boolean basenameOnly = normalized.indexOf('/') < 0;
        String prefix = basenameOnly ? "(?:^|.*/)" : "^";
        return new PathPatternMatcher(Pattern.compile(prefix + toRegex(normalized) + "$"));
    }

    static PathPatternMatcher rootedGlob(String glob) {
        String normalized = normalize(glob);
        return new PathPatternMatcher(Pattern.compile("^" + toRegex(normalized) + "$"));
    }

    boolean matches(String relativePath) {
        return pattern.matcher(relativePath.replace('\\', '/')).matches();
    }

    private static String normalize(String glob) {
        if (glob == null || glob.isBlank()) {
            throw new IllegalArgumentException("glob pattern is required");
        }
        return glob.replace('\\', '/');
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                index = appendAsterisk(regex, glob, index);
            } else if (current == '?') {
                regex.append("[^/]");
            } else if (current == '[') {
                index = appendCharacterClass(regex, glob, index);
            } else {
                appendLiteral(regex, current);
            }
        }
        return regex.toString();
    }

    private static int appendAsterisk(StringBuilder regex, String glob, int index) {
        boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
        if (!doubleStar) {
            regex.append("[^/]*");
            return index;
        }
        boolean followedBySlash = index + 2 < glob.length() && glob.charAt(index + 2) == '/';
        regex.append(followedBySlash ? "(?:.*/)?" : ".*");
        return followedBySlash ? index + 2 : index + 1;
    }

    private static int appendCharacterClass(StringBuilder regex, String glob, int index) {
        int end = glob.indexOf(']', index + 1);
        if (end < 0) {
            regex.append("\\[");
            return index;
        }
        regex.append(glob, index, end + 1);
        return end;
    }

    private static void appendLiteral(StringBuilder regex, char value) {
        if (".(){}+$^|\\".indexOf(value) >= 0) {
            regex.append('\\');
        }
        regex.append(value);
    }
}
