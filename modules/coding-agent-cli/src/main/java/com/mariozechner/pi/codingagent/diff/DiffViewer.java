package com.mariozechner.pi.codingagent.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Colorized side-by-side diff visualization for terminal display.
 */
public class DiffViewer {

    /** ANSI color codes. */
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";
    private static final String BG_RED = "\033[41m";
    private static final String BG_GREEN = "\033[42m";

    public enum LineType { SAME, ADDED, REMOVED, MODIFIED }

    public record DiffLine(LineType type, int oldLineNum, int newLineNum, String oldText, String newText) {}

    /**
     * Compute a simple line-based diff between two texts.
     */
    public static List<DiffLine> diff(String oldText, String newText) {
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);
        return computeLcs(oldLines, newLines);
    }

    /**
     * Format diff as colored unified diff string.
     */
    public static String formatUnified(List<DiffLine> lines, String fileName) {
        var sb = new StringBuilder();
        sb.append(CYAN).append("--- a/").append(fileName).append(RESET).append('\n');
        sb.append(CYAN).append("+++ b/").append(fileName).append(RESET).append('\n');

        for (DiffLine line : lines) {
            switch (line.type) {
                case SAME -> sb.append(DIM).append("  ").append(line.oldText).append(RESET).append('\n');
                case REMOVED -> sb.append(RED).append("- ").append(line.oldText).append(RESET).append('\n');
                case ADDED -> sb.append(GREEN).append("+ ").append(line.newText).append(RESET).append('\n');
                case MODIFIED -> {
                    sb.append(RED).append("- ").append(line.oldText).append(RESET).append('\n');
                    sb.append(GREEN).append("+ ").append(line.newText).append(RESET).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Format diff as side-by-side view.
     */
    public static String formatSideBySide(List<DiffLine> lines, int colWidth) {
        var sb = new StringBuilder();
        String separator = " │ ";
        String headerFmt = "%-" + colWidth + "s" + separator + "%-" + colWidth + "s";
        sb.append(CYAN).append(String.format(headerFmt, "Old", "New")).append(RESET).append('\n');
        sb.append("─".repeat(colWidth)).append("─┼─").append("─".repeat(colWidth)).append('\n');

        for (DiffLine line : lines) {
            String left = truncate(line.oldText != null ? line.oldText : "", colWidth);
            String right = truncate(line.newText != null ? line.newText : "", colWidth);
            String leftFmt = "%-" + colWidth + "s";
            String rightFmt = "%-" + colWidth + "s";

            switch (line.type) {
                case SAME -> sb.append(DIM)
                    .append(String.format(leftFmt, left)).append(separator)
                    .append(String.format(rightFmt, right)).append(RESET).append('\n');
                case REMOVED -> sb.append(RED)
                    .append(String.format(leftFmt, left)).append(RESET).append(separator)
                    .append(String.format(rightFmt, "")).append('\n');
                case ADDED -> sb.append(String.format(leftFmt, "")).append(separator)
                    .append(GREEN).append(String.format(rightFmt, right)).append(RESET).append('\n');
                case MODIFIED -> sb.append(RED)
                    .append(String.format(leftFmt, left)).append(RESET).append(separator)
                    .append(GREEN).append(String.format(rightFmt, right)).append(RESET).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Generate a summary of changes.
     */
    public static DiffSummary summarize(List<DiffLine> lines) {
        int added = 0, removed = 0, modified = 0, unchanged = 0;
        for (DiffLine line : lines) {
            switch (line.type) {
                case ADDED -> added++;
                case REMOVED -> removed++;
                case MODIFIED -> modified++;
                case SAME -> unchanged++;
            }
        }
        return new DiffSummary(added, removed, modified, unchanged);
    }

    public record DiffSummary(int added, int removed, int modified, int unchanged) {
        public String format() {
            return GREEN + "+" + added + RESET + " "
                + RED + "-" + removed + RESET + " "
                + YELLOW + "~" + modified + RESET;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    /** Simple LCS-based diff algorithm. */
    private static List<DiffLine> computeLcs(String[] oldLines, String[] newLines) {
        int m = oldLines.length, n = newLines.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m || j < n) {
            if (i < m && j < n && oldLines[i].equals(newLines[j])) {
                result.add(new DiffLine(LineType.SAME, i + 1, j + 1, oldLines[i], newLines[j]));
                i++; j++;
            } else if (j < n && (i >= m || dp[i][j + 1] >= dp[i + 1][j])) {
                result.add(new DiffLine(LineType.ADDED, -1, j + 1, null, newLines[j]));
                j++;
            } else {
                result.add(new DiffLine(LineType.REMOVED, i + 1, -1, oldLines[i], null));
                i++;
            }
        }
        return result;
    }
}
