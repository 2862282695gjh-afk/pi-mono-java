package com.mariozechner.pi.codingagent.mode.tui;

import com.mariozechner.pi.agent.tool.AgentToolResult;
import com.mariozechner.pi.ai.types.ContentBlock;
import com.mariozechner.pi.ai.types.TextContent;
import com.mariozechner.pi.codingagent.tool.edit.EditToolDetails;
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
    private String partialResultSummary;

    public ToolStatusComponent(String toolName) {
        this.toolName = toolName;
    }

    public void setArgs(Object args) {
        this.args = args;
    }

    /** Update with partial result (live progress during tool execution). */
    public void updatePartialResult(Object partialResult) {
        this.partialResultSummary = summarizeResult(partialResult);
    }

    public void setComplete(boolean error, Object result) {
        this.complete = true;
        this.error = error;
        this.resultSummary = summarizeResult(result);
        this.partialResultSummary = null;
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

        // Args summary — tool-specific formatting
        if (args != null) {
            String argSummary = formatToolArgs(toolName, args);
            if (!argSummary.isEmpty()) {
                int contentWidth = Math.max(1, width - 4);
                String truncated = truncateText(argSummary, contentWidth);
                lines.add(ANSI_DIM + "    " + truncated + ANSI_RESET);
            }
        }

        // Partial result (while running)
        if (!complete && partialResultSummary != null && !partialResultSummary.isEmpty()) {
            int contentWidth = Math.max(1, width - 4);
            String[] partialLines = partialResultSummary.split("\n");
            int linesToShow = Math.min(partialLines.length, MAX_RESULT_DISPLAY_LINES);
            for (int i = 0; i < linesToShow; i++) {
                String truncated = truncateText(partialLines[i], contentWidth);
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

    /**
     * Formats tool arguments with tool-specific highlighting.
     * Shows the most relevant parameter for each tool type.
     */
    private static String formatToolArgs(String toolName, Object args) {
        if (args instanceof Map<?, ?> map) {
            return switch (toolName) {
                case "bash" -> {
                    Object cmd = map.get("command");
                    yield cmd != null ? "$ " + truncateValue(cmd.toString(), 100) : summarizeArgs(args);
                }
                case "read" -> {
                    Object path = map.get("file_path");
                    yield path != null ? path.toString() : summarizeArgs(args);
                }
                case "write" -> {
                    Object path = map.get("file_path");
                    yield path != null ? path.toString() : summarizeArgs(args);
                }
                case "edit" -> {
                    Object path = map.get("file_path");
                    yield path != null ? path.toString() : summarizeArgs(args);
                }
                case "grep" -> {
                    Object pattern = map.get("pattern");
                    Object path = map.get("path");
                    String s = pattern != null ? "pattern: " + pattern : "";
                    if (path != null) s += (s.isEmpty() ? "" : ", ") + "path: " + path;
                    yield s.isEmpty() ? summarizeArgs(args) : s;
                }
                case "glob" -> {
                    Object pattern = map.get("pattern");
                    Object path = map.get("path");
                    String s = pattern != null ? pattern.toString() : "";
                    if (path != null) s += (s.isEmpty() ? "" : " in ") + path;
                    yield s.isEmpty() ? summarizeArgs(args) : s;
                }
                case "ls" -> {
                    Object path = map.get("path");
                    yield path != null ? path.toString() : summarizeArgs(args);
                }
                default -> summarizeArgs(args);
            };
        }
        return summarizeArgs(args);
    }

    private static String truncateValue(String value, int max) {
        if (value.length() > max) return value.substring(0, max - 3) + "...";
        return value;
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
        String s;
        if (result instanceof AgentToolResult atr && atr.content() != null) {
            var sb = new StringBuilder();
            for (ContentBlock block : atr.content()) {
                if (block instanceof TextContent tc) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(tc.text());
                }
            }
            // Append diff from EditToolDetails if available
            if (atr.details() instanceof EditToolDetails details && details.diff() != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(formatDiff(details.diff()));
            }
            s = sb.toString();
        } else {
            s = result.toString();
        }
        if (s.isBlank()) return null;
        // Limit to reasonable size
        if (s.length() > 500) {
            return s.substring(0, 500) + "...";
        }
        return s;
    }

    /** Format a unified diff with red (-) and green (+) coloring. */
    private static String formatDiff(String diff) {
        var sb = new StringBuilder();
        for (String line : diff.split("\n")) {
            if (line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@")) {
                continue; // Skip diff headers
            }
            if (!sb.isEmpty()) sb.append('\n');
            if (line.startsWith("-")) {
                sb.append(ANSI_RED).append(line).append(ANSI_RESET);
            } else if (line.startsWith("+")) {
                sb.append(ANSI_GREEN).append(line).append(ANSI_RESET);
            } else {
                sb.append(ANSI_DIM).append(line).append(ANSI_RESET);
            }
        }
        return sb.toString();
    }

    private static String truncateText(String text, int maxWidth) {
        if (text == null) return "";
        int visWidth = AnsiUtils.visibleWidth(text);
        if (visWidth <= maxWidth) return text;
        return AnsiUtils.sliceByColumn(text, 0, Math.max(1, maxWidth - 3)) + "...";
    }
}
