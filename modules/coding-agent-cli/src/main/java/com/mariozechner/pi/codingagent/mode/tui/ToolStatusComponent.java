package com.mariozechner.pi.codingagent.mode.tui;

import com.mariozechner.pi.tui.Component;
import com.mariozechner.pi.tui.ansi.AnsiUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Displays a tool execution status with arguments and result summary.
 * Matches pi-mono TS tool display:
 * <pre>
 *   [toolName] running...
 *   args summary
 *
 *   [toolName] done
 *   result summary (truncated)
 * </pre>
 */
public class ToolStatusComponent implements Component {

    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_BOLD = "\033[1m";
    private static final String ANSI_DIM = "\033[2m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_GREEN = "\033[32m";

    private static final int MAX_ARG_DISPLAY_LEN = 120;
    private static final int MAX_RESULT_DISPLAY_LINES = 3;

    private final String toolName;
    private Object args;
    private boolean complete;
    private boolean error;
    private String resultSummary;

    public ToolStatusComponent(String toolName) {
        this.toolName = toolName;
    }

    public void setArgs(Object args) {
        this.args = args;
    }

    public void setComplete(boolean error, Object result) {
        this.complete = true;
        this.error = error;
        this.resultSummary = summarizeResult(result);
        invalidate();
    }

    public void setComplete(boolean error) {
        setComplete(error, null);
    }

    @Override
    public List<String> render(int width) {
        var lines = new ArrayList<String>();

        // Status line
        String statusText;
        if (!complete) {
            statusText = ANSI_YELLOW + ANSI_BOLD + "  [" + toolName + "]"
                    + ANSI_RESET + ANSI_DIM + " running..." + ANSI_RESET;
        } else if (error) {
            statusText = ANSI_YELLOW + ANSI_BOLD + "  [" + toolName + "]"
                    + ANSI_RESET + " " + ANSI_RED + "failed" + ANSI_RESET;
        } else {
            statusText = ANSI_YELLOW + ANSI_BOLD + "  [" + toolName + "]"
                    + ANSI_RESET + " " + ANSI_GREEN + "done" + ANSI_RESET;
        }
        lines.add(statusText);

        // Args summary (when running or done)
        if (args != null) {
            String argSummary = summarizeArgs(args);
            if (!argSummary.isEmpty()) {
                int contentWidth = Math.max(1, width - 4);
                String truncated = truncateText(argSummary, contentWidth);
                lines.add(ANSI_DIM + "    " + truncated + ANSI_RESET);
            }
        }

        // Result summary (when complete)
        if (complete && resultSummary != null && !resultSummary.isEmpty()) {
            int contentWidth = Math.max(1, width - 4);
            String[] resultLines = resultSummary.split("\n");
            int linesToShow = Math.min(resultLines.length, MAX_RESULT_DISPLAY_LINES);
            for (int i = 0; i < linesToShow; i++) {
                String truncated = truncateText(resultLines[i], contentWidth);
                lines.add(ANSI_DIM + "    " + truncated + ANSI_RESET);
            }
            if (resultLines.length > MAX_RESULT_DISPLAY_LINES) {
                lines.add(ANSI_DIM + "    ..." + (resultLines.length - MAX_RESULT_DISPLAY_LINES)
                        + " more lines" + ANSI_RESET);
            }
        }

        return lines;
    }

    @Override
    public void invalidate() {
        // No cache
    }

    @SuppressWarnings("unchecked")
    private static String summarizeArgs(Object args) {
        if (args == null) return "";
        if (args instanceof Map<?, ?> map) {
            var sb = new StringBuilder();
            for (var entry : map.entrySet()) {
                if (!sb.isEmpty()) sb.append(", ");
                String key = String.valueOf(entry.getKey());
                String value = String.valueOf(entry.getValue());
                // Truncate long values
                if (value.length() > 80) {
                    value = value.substring(0, 77) + "...";
                }
                sb.append(key).append(": ").append(value);
                if (sb.length() > MAX_ARG_DISPLAY_LEN) {
                    return sb.substring(0, MAX_ARG_DISPLAY_LEN) + "...";
                }
            }
            return sb.toString();
        }
        String s = args.toString();
        if (s.length() > MAX_ARG_DISPLAY_LEN) {
            return s.substring(0, MAX_ARG_DISPLAY_LEN) + "...";
        }
        return s;
    }

    private static String summarizeResult(Object result) {
        if (result == null) return null;
        String s = result.toString();
        if (s.isBlank()) return null;
        // Limit to reasonable size
        if (s.length() > 500) {
            return s.substring(0, 500) + "...";
        }
        return s;
    }

    private static String truncateText(String text, int maxWidth) {
        if (text == null) return "";
        int visWidth = AnsiUtils.visibleWidth(text);
        if (visWidth <= maxWidth) return text;
        return AnsiUtils.sliceByColumn(text, 0, Math.max(1, maxWidth - 3)) + "...";
    }
}
