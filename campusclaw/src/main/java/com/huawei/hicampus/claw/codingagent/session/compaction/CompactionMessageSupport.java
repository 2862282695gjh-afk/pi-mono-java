/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session.compaction;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.UserMessage;

/**
 * 在公共 Session 与 Runtime 恢复层之间统一压缩摘要消息格式。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public final class CompactionMessageSupport {
    private static final String SUMMARY_PREFIX =
            """
            The conversation history before this point was compacted into the following summary:

            <summary>
            """;

    private static final String SUMMARY_SUFFIX = "\n</summary>";

    private static final String READ_FILES_START = "<read-files>\n";

    private static final String READ_FILES_END = "\n</read-files>";

    private CompactionMessageSupport() {}

    public static UserMessage summaryMessage(String summary, long timestamp) {
        return new UserMessage(SUMMARY_PREFIX + summary + SUMMARY_SUFFIX, timestamp);
    }

    static String previousSummary(List<Message> messages) {
        if (messages.isEmpty() || !(messages.getFirst() instanceof UserMessage user)) {
            return null;
        }
        String text = text(user.content());
        if (!text.startsWith(SUMMARY_PREFIX) || !text.endsWith(SUMMARY_SUFFIX)) {
            return null;
        }
        return text.substring(SUMMARY_PREFIX.length(), text.length() - SUMMARY_SUFFIX.length());
    }

    static long latestBoundaryTimestamp(List<Message> messages) {
        return previousSummary(messages) == null ? -1L : ((UserMessage) messages.getFirst()).timestamp();
    }

    static int boundaryStart(List<Message> messages) {
        return previousSummary(messages) == null ? 0 : 1;
    }

    static Set<String> readFiles(String summary) {
        Set<String> files = new TreeSet<>();
        if (summary == null) {
            return files;
        }
        int start = summary.indexOf(READ_FILES_START);
        if (start < 0) {
            return files;
        }
        int contentStart = start + READ_FILES_START.length();
        int end = summary.indexOf(READ_FILES_END, contentStart);
        if (end < 0) {
            return files;
        }
        summary.substring(contentStart, end)
                .lines()
                .filter(line -> !line.isBlank())
                .forEach(files::add);
        return files;
    }

    static String appendReadFiles(String summary, Set<String> files) {
        if (files.isEmpty()) {
            return summary;
        }
        return summary + "\n\n" + READ_FILES_START + String.join("\n", files) + READ_FILES_END;
    }

    private static String text(List<ContentBlock> content) {
        return content.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
    }
}
